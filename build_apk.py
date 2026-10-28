#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""零依赖 APK 打包器：文本 Manifest -> AXML 二进制 + javac + d8 + v2 签名。

背景：本机是 aarch64，Google 的 aapt2/zipalign 只有 x86_64 版。本脚本：
  1) 自己把 AndroidManifest.xml 编译成 binary AXML（Android 只认二进制清单）；
  2) javac 编译源码（aarch64 JDK + android.jar）；
  3) d8（纯 Java，build-tools 的 d8.jar）转 dex；
  4) zip 组装 APK（manifest + dex + assets，无任何资源文件）；
  5) apksigner.jar（纯 Java）做 v2 签名。

AXML 结构（参照 AOSP ResourceTypes.cpp）：
  ResXML(type=0x0003) + StringPool(UTF-8) + [无 ResourceMap，框架按字符串匹配属性]
  + StartNamespace(android前缀) + Elements… + EndNamespace
"""
from __future__ import annotations

import os
import shutil
import struct
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
H = os.path.expanduser("~/android-build")
JDK = os.path.join(H, "jdk")
BT_DIR = os.path.join(H, "pkg", "bt")
ANDROID_JAR = os.path.join(H, "pkg", "android.jar")
BUILD = os.path.join(HERE, "build")

ANDROID_NS = "http://schemas.android.com/apk/res/android"
NS = {"android": ANDROID_NS}

# ---- AXML 常量 ----
RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_START_NAMESPACE = 0x0100
RES_XML_END_NAMESPACE = 0x0101
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
UTF8_FLAG = 0x100
NO_ENTRY = 0xFFFFFFFF

TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12

RES_XML_RESOURCE_MAP = 0x0180

RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_SPEC = 0x0202
RES_TABLE_TYPE_TYPE = 0x0201

TYPE_REFERENCE = 0x01

# 属性名 -> android attr 资源 id（javap -constants android.R$attr 权威值）。
# 池的前 N 项必须是有 id 的属性名，map[i] 与池[i] 一一对齐（金标准 APK 实测规则）。
ATTR_IDS = {
    "name": 0x01010003,
    "label": 0x01010001,
    "icon": 0x01010002,
    "theme": 0x01010000,
    "exported": 0x01010010,
    "allowBackup": 0x01010280,
    "versionCode": 0x0101021B,
    "versionName": 0x0101021C,
    "minSdkVersion": 0x0101020C,
    "targetSdkVersion": 0x01010270,
}

# @引用写法 -> 资源 id（package 0x7f / type 1 / entry 0）
REF_IDS = {
    "@mipmap/ic_launcher": 0x7f010000,
    # 框架主题（android.R.style.Theme_Material_Light）：浅色 Material
    "@android:style/Theme.Material.Light": 0x01030237,
    # 框架主题（android.R.style.Theme_Material_NoActionBar）：深色、无系统标题栏
    "@android:style/Theme.Material.NoActionBar": 0x0103022E,
}


def _u8_len(n: int) -> bytes:
    """UTF-8 字符串池长度前缀（变长 u8）。"""
    if n >= 0x80:
        return bytes([0x80 | ((n >> 8) & 0x7F), n & 0xFF])
    return bytes([n])


class StringPool:
    def __init__(self):
        self.items: list[str] = []
        self.index: dict[str, int] = {}

    def add(self, s: str) -> int:
        if s in self.index:
            return self.index[s]
        i = len(self.items)
        self.items.append(s)
        self.index[s] = i
        return i

    def chunk(self) -> bytes:
        # UTF-8 编码的字符串数据
        offsets = []
        data = bytearray()
        for s in self.items:
            offsets.append(len(data))
            raw = s.encode("utf-8")
            data += _u8_len(len(s))       # 字符数（近似，ASCII 与字节数一致即可）
            data += _u8_len(len(raw))     # 字节数
            data += raw + b"\x00"
        while len(data) % 4:
            data += b"\x00"
        count = len(self.items)
        header_size = 28
        strings_start = header_size + 4 * count
        size = strings_start + len(data)
        out = struct.pack("<HHIIIIII",
                          RES_STRING_POOL_TYPE, header_size, size,
                          count, 0, UTF8_FLAG, strings_start, 0)
        out += struct.pack(f"<{count}I", *offsets) if count else b""
        return out + bytes(data)


def _chunk_header(typ: int, header_size: int, size: int) -> bytes:
    return struct.pack("<HHI", typ, header_size, size)


def _line_comment() -> bytes:
    return struct.pack("<II", 1, NO_ENTRY)


def compile_axml(manifest_path: str) -> bytes:
    """文本 Manifest -> binary AXML bytes（含 resource map，按金标准布局）。"""
    tree = ET.parse(manifest_path)
    root = tree.getroot()

    def local(tag: str) -> str:
        return tag.split("}")[-1] if "}" in tag else tag

    # 1) 统计用到的属性名，分“有 attr id / 无 id”
    used_with_id = []
    for el in tree.iter():
        for k in el.attrib:
            n = local(k)
            if n in ATTR_IDS and n not in used_with_id:
                used_with_id.append(n)

    pool = StringPool()
    # 2) 池顺序：有 id 的属性名占前 N 项（resource map 按索引对齐池前缀）
    for n in used_with_id:
        pool.add(n)
    n_mapped = len(used_with_id)
    # 3) 然后是 namespace 字符串
    prefix_idx = pool.add("android")
    uri_idx = pool.add(ANDROID_NS)

    # 解析元素树为中间结构
    def walk(el: ET.Element):
        name = local(el.tag)
        pool.add(name)
        attrs = []  # (ns_idx|NO_ENTRY, name_idx, raw_idx|NO, type, data)
        for k, v in el.attrib.items():
            if k.startswith("{"):
                ns_uri = k[1:k.index("}")]
                ns_i = uri_idx if ns_uri == ANDROID_NS else pool.add(ns_uri)
                a_name = local(k)
            else:
                ns_i = NO_ENTRY
                a_name = k
            n_idx = pool.add(a_name)
            # 类型推断：全数字 -> int；true/false -> boolean；否则字符串
            vs = str(v)
            if vs in ("true", "false"):
                attrs.append((ns_i, n_idx, NO_ENTRY, TYPE_INT_BOOLEAN,
                              0xFFFFFFFF if vs == "true" else 0))
            elif vs.startswith("@") and vs in REF_IDS:
                # 资源引用（如 @mipmap/ic_launcher）→ TYPE_REFERENCE
                attrs.append((ns_i, n_idx, NO_ENTRY, TYPE_REFERENCE, REF_IDS[vs]))
            elif vs.lstrip("-").isdigit() and not a_name.isupper():
                # versionCode/minSdk 等整型；版本号字符串"1.0"含点走字符串
                attrs.append((ns_i, n_idx, NO_ENTRY, TYPE_INT_DEC, int(vs)))
            else:
                s_idx = pool.add(vs)
                attrs.append((ns_i, n_idx, s_idx, TYPE_STRING, s_idx))
        yield ("start", name, attrs)
        for child in el:
            yield from walk(child)
        yield ("end", name, None)

    # 先建好完整中间表示，再统一生成（池在 body 之前定稿）
    body_ops = list(walk(root))

    # ---- 组装文件 ----
    pool_chunk = pool.chunk()

    # resource map：type=0x0180, headerSize=8, ids[i] 对齐字符串池第 i 项。
    # 金标准 APK 实测：池前 N 项 = 有 attr id 的属性名，紧跟 map（紧邻池尾）。
    map_chunk = _chunk_header(RES_XML_RESOURCE_MAP, 8, 8 + 4 * n_mapped) + \
        struct.pack(f"<{n_mapped}I",
                    *[ATTR_IDS[pool.items[i]] for i in range(n_mapped)])

    ns_chunk = (_chunk_header(RES_XML_START_NAMESPACE, 16, 24) + _line_comment()
                + struct.pack("<II", prefix_idx, uri_idx))

    elems = bytearray()

    def start_el(name: str, attrs):
        n_idx = pool.index[name]
        ext = struct.pack("<IIHHHHHH", NO_ENTRY, n_idx, 20, 20,
                          len(attrs), 0, 0, 0)
        ablob = b""
        for ns_i, a_n, raw, typ, data in attrs:
            ablob += struct.pack("<III", ns_i, a_n, raw)
            ablob += struct.pack("<HBBI", 8, 0, typ, data)
        size = 16 + len(ext) + len(ablob)
        elems.extend(_chunk_header(RES_XML_START_ELEMENT, 16, size))
        elems.extend(_line_comment())
        elems.extend(ext)
        elems.extend(ablob)

    def end_el(name: str):
        n_idx = pool.index[name]
        elems.extend(_chunk_header(RES_XML_END_ELEMENT, 16, 24))
        elems.extend(_line_comment())
        elems.extend(struct.pack("<II", NO_ENTRY, n_idx))

    for op in body_ops:
        if op[0] == "start":
            start_el(op[1], op[2])
        else:
            end_el(op[1])

    end_ns = (_chunk_header(RES_XML_END_NAMESPACE, 16, 24) + _line_comment()
              + struct.pack("<II", prefix_idx, uri_idx))

    total = 8 + len(pool_chunk) + len(map_chunk) + len(ns_chunk) + len(elems) + len(end_ns)
    out = _chunk_header(RES_XML_TYPE, 8, total)
    out += pool_chunk + map_chunk + ns_chunk + bytes(elems) + end_ns
    return out


def build_arsc(package_name: str, entries: list) -> bytes:
    """生成最小可用 resources.arsc（结构对齐真实 APK 实测结果）。

    entries: [(type_name, entry_name, file_path)]，例如
             [("mipmap", "ic_launcher", "res/mipmap/ic_launcher.png")]

    布局：
      ResTable_header(12, packageCount=1)
        -> 全局值字符串池（存放文件路径）
        -> Package(0x0200, headerSize=288, id=0x7f, name 128×UTF-16)
             { typeStrings池, keyStrings池, TypeSpec(0x0202), Type(0x0201) }
    """
    gpool = StringPool()
    for _, _, path in entries:
        gpool.add(path)
    global_pool = gpool.chunk()

    tpool = StringPool()
    type_names = []
    for tname, _, _ in entries:
        if tname not in type_names:
            type_names.append(tname)
        tpool.add(tname)
    kpool = StringPool()
    for _, ename, _ in entries:
        kpool.add(ename)
    type_pool = tpool.chunk()
    key_pool = kpool.chunk()

    PKG_HEADER_SIZE = 288
    type_strings_off = PKG_HEADER_SIZE
    key_strings_off = type_strings_off + len(type_pool)
    body_off = key_strings_off + len(key_pool)

    body = bytearray()
    for ti, tname in enumerate(type_names):
        type_id = ti + 1
        group = [e for e in entries if e[0] == tname]
        entry_count = len(group)

        # TypeSpec：16 + 4*entryCount
        spec = _chunk_header(RES_TABLE_TYPE_SPEC, 16, 16 + 4 * entry_count)
        spec += struct.pack('<BBHI', type_id, 0, 0, entry_count)
        spec += b'\x00' * (4 * entry_count)
        body += spec

        # Type：headerSize=76（8 + 12 + 56 字节 config）
        entries_start = 76 + 4 * entry_count
        offsets = []
        entry_blob = bytearray()
        for e in group:
            offsets.append(len(entry_blob))
            key_idx = kpool.index[e[1]]
            global_idx = gpool.index[e[2]]
            entry_blob += struct.pack('<HHI', 8, 0, key_idx)                   # ResTable_entry
            entry_blob += struct.pack('<HBBI', 8, 0, TYPE_STRING, global_idx)  # Res_value
        size = entries_start + len(entry_blob)
        ch = _chunk_header(RES_TABLE_TYPE_TYPE, 76, size)
        ch += struct.pack('<BBHII', type_id, 0, 0, entry_count, entries_start)
        # ResTable_config：首字段 size 必须等于 config 结构大小（56），其余为默认配置
        ch += struct.pack('<I', 56) + b'\x00' * 52
        ch += struct.pack(f'<{entry_count}I', *offsets)
        ch += bytes(entry_blob)
        body += ch

    pkg_size = body_off + len(body)
    name_field = package_name.encode('utf-16-le')[:256].ljust(256, b'\x00')
    pkg = _chunk_header(RES_TABLE_PACKAGE_TYPE, PKG_HEADER_SIZE, pkg_size)
    pkg += struct.pack('<I', 0x7f)
    pkg += name_field
    pkg += struct.pack('<IIII', type_strings_off, len(type_names),
                       key_strings_off, len(kpool.items))
    pkg += struct.pack('<I', 0)     # typeIdOffset
    assert len(pkg) == PKG_HEADER_SIZE, len(pkg)

    total = 12 + len(global_pool) + pkg_size
    out = _chunk_header(RES_TABLE_TYPE, 12, total)
    out += struct.pack('<I', 1)     # packageCount
    out += global_pool + pkg + type_pool + key_pool + bytes(body)
    return out


def _write_entry(zf: zipfile.ZipFile, arcname: str, data: bytes,
                 store: bool = False, align: int = 0):
    """写入 ZIP 条目。

    store=True：不压缩（ZIP_STORED）。
    align=N   ：用 Android 的 0xD935 填充记录，把该条目的数据起始偏移对齐到 N 字节
                （与 zipalign 同一手法；Android 11+ 对 targetSdk≥30 的 APK 要求
                 resources.arsc 不压缩且 4 字节对齐，否则报 -124）。
    """
    zi = zipfile.ZipInfo(arcname, date_time=(1980, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED if store else zipfile.ZIP_DEFLATED
    zi.external_attr = 0o644 << 16
    if align > 1:
        name_len = len(arcname.encode("utf-8"))
        cur = zf.fp.tell()
        # 数据偏移 = cur + 30(本地头) + name_len + (4 + pad)
        pad = (-(cur + 34 + name_len)) % align
        zi.extra = struct.pack("<HH", 0xD935, pad) + b"\x00" * pad
    zf.writestr(zi, data)


def run(cmd, **kw):
    print("+", " ".join(os.path.basename(c) if os.path.sep in c else c for c in cmd))
    r = subprocess.run(cmd, **kw)
    if r.returncode != 0:
        print(f"FAILED (exit {r.returncode})")
        sys.exit(r.returncode)


def main():
    javac = os.path.join(JDK, "bin", "javac")
    java = os.path.join(JDK, "bin", "java")
    keytool = os.path.join(JDK, "bin", "keytool")

    for p in (javac, java, ANDROID_JAR):
        if not os.path.exists(p):
            print(f"缺少工具链: {p}（先跑 download_toolchain.sh）")
            sys.exit(1)
    d8_jar = os.path.join(BT_DIR, "lib", "d8.jar")
    apksigner_jar = os.path.join(BT_DIR, "lib", "apksigner.jar")
    for p in (d8_jar, apksigner_jar):
        if not os.path.exists(p):
            print(f"缺少 build-tools 组件: {p}（先跑 download_toolchain.sh）")
            sys.exit(1)

    shutil.rmtree(BUILD, ignore_errors=True)
    for d in ("obj", "dex"):
        os.makedirs(os.path.join(BUILD, d))

    # 1) Manifest -> binary AXML
    axml = compile_axml(os.path.join(HERE, "AndroidManifest.xml"))
    with open(os.path.join(BUILD, "AndroidManifest.xml"), "wb") as f:
        f.write(axml)
    print(f"AXML: {len(axml)} bytes")

    # 2) javac
    sources = []
    for root, _, files in os.walk(os.path.join(HERE, "src")):
        for fn in files:
            if fn.endswith(".java"):
                sources.append(os.path.join(root, fn))
    run([javac, "-source", "8", "-target", "8", "-nowarn",
         "-classpath", ANDROID_JAR, "-d", os.path.join(BUILD, "obj")]
        + sources)

    # 3) d8
    classes = []
    for root, _, files in os.walk(os.path.join(BUILD, "obj")):
        for fn in files:
            if fn.endswith(".class"):
                classes.append(os.path.join(root, fn))
    run([java, "-cp", d8_jar, "com.android.tools.r8.D8",
         "--release", "--lib", ANDROID_JAR,
         "--output", os.path.join(BUILD, "dex")] + classes)

    # 4) 组装（含自定义图标：resources.arsc + res/mipmap/ic_launcher.png）
    icon_src = os.path.join(HERE, "res", "mipmap", "ic_launcher.png")
    icon_entry = "res/mipmap/ic_launcher.png"
    has_icon = os.path.isfile(icon_src)
    if has_icon:
        arsc = build_arsc("com.example.mockgps",
                          [("mipmap", "ic_launcher", icon_entry)])
        with open(os.path.join(BUILD, "resources.arsc"), "wb") as f:
            f.write(arsc)
        print(f"ARSC: {len(arsc)} bytes (1 entry: mipmap/ic_launcher)")
    else:
        print("WARN: 未找到 res/mipmap/ic_launcher.png，将不带自定义图标")

    unsigned = os.path.join(BUILD, "unsigned.apk")
    with zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as z:
        _write_entry(z, "AndroidManifest.xml",
                     open(os.path.join(BUILD, "AndroidManifest.xml"), "rb").read())
        _write_entry(z, "classes.dex",
                     open(os.path.join(BUILD, "dex", "classes.dex"), "rb").read())
        _write_entry(z, "assets/track.json",
                     open(os.path.join(HERE, "assets", "track.json"), "rb").read())
        if has_icon:
            # Android 11+（targetSdk≥30）硬性要求：resources.arsc 必须
            # 「不压缩 + 4 字节对齐」，否则安装报错 -124。
            _write_entry(z, "resources.arsc", arsc, store=True, align=4)
            _write_entry(z, icon_entry, open(icon_src, "rb").read())

    # 5) v2 签名（首次生成 keystore）
    ks = os.path.join(HERE, "debug.jks")
    if not os.path.exists(ks):
        run([keytool, "-genkeypair", "-keystore", ks, "-alias", "mockgps",
             "-storepass", "android", "-keypass", "android",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=MockGPS, OU=test"])
    out_apk = os.path.join(HERE, "拖跑.apk")
    if os.path.exists(out_apk):
        os.remove(out_apk)
    run([java, "-jar", apksigner_jar, "sign",
         "--ks", ks, "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--v2-signing-enabled", "true",
         "--out", out_apk, unsigned])
    run([java, "-jar", apksigner_jar, "verify", "--print-certs", out_apk])
    # ASCII 别名：方便通过 URL 下载（内容完全相同）
    alias = os.path.join(HERE, "tuopao.apk")
    shutil.copyfile(out_apk, alias)
    print(f"BUILD_OK: {out_apk} ({os.path.getsize(out_apk)} bytes); alias={alias}")


if __name__ == "__main__":
    main()
