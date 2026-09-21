#!/usr/bin/env node
/**
 * mdns-announce.mjs —— 在局域网广播 `_dsh._tcp.local`，让 **DSH Mobile** 免扫 254 个地址即时发现你的 DSH。
 *
 * 为什么需要它：
 *   DSH Mobile 有两种发现方式 —— ① 主动扫本子网的 3081/3080（慢，约 3~5 秒，且只能发现同一子网）；
 *   ② **mDNS/NSD 发现**（即时）。方式 ② 需要**服务端先广播** `_dsh._tcp.local`，而 DSH 本体不广播任何服务
 *   → 于是有了这个**零依赖**的小脚本：在你跑 DSH 的电脑上常驻，手机就能"秒发现"。
 *   （不想要它也行 —— 手机仍可用方式 ① 手动扫，或在设置里直接手填地址。）
 *
 * 双端契约（与 App 侧一致）：
 *   服务类型 `_dsh._tcp.local` · 端口取自 **SRV**（默认广播 **3081** = dsh-pocket 局域网入口）
 *   · TXT = `ver=<版本>` + `pin=1`（该入口需要 8 位局域网密码）
 *
 * 用法：
 *   node mdns-announce.mjs                 # 前台运行（Ctrl+C 退出）
 *   node mdns-announce.mjs --quiet         # 不打印每次查询
 *   node mdns-announce.mjs --port 3082     # 换成别的端口广播
 * 环境变量：DSH_MDNS_PORT · DSH_MDNS_VER · DSH_MDNS_NAME · DSH_MDNS_PIDFILE
 *
 * 实现要点（自己写一个也照这几条走）：
 *   1. **必须过滤 QR 位**：mDNS 自己发出的应答会经组播回环回到本 socket，若不过滤就会把应答当查询
 *      → **自环放大成群播风暴**（实测能刷出上万包）。
 *   2. **PTR 应答的 additional 段要带 SRV+TXT+A**：只回 PTR 时，部分解析器（实测 bonjour-service）
 *      能收到却**不触发发现**。
 *   3. **按网卡分别应答**：多网卡（有线 + 无线）时，必须用**收到查询的那块网卡**的地址回答，
 *      否则客户端可能拿到一个自己不可达的地址。
 *
 * 无任何依赖，只用 Node 内置模块（需 Node ≥ 18）。
 */

import dgram from 'node:dgram'
import os from 'node:os'
import { writeFileSync, unlinkSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const MDNS_ADDR = '224.0.0.251'
const MDNS_PORT = 5353
const SERVICE = '_dsh._tcp.local'
const TTL = 4500

/* ── 参数 ── */
const argv = process.argv.slice(2)
const QUIET = argv.includes('--quiet')
const argOf = (name) => {
  const i = argv.indexOf(name)
  return i >= 0 && argv[i + 1] ? argv[i + 1] : undefined
}
const ADVERT_PORT = Number(argOf('--port') || process.env.DSH_MDNS_PORT || 3081)
const VER = argOf('--ver') || process.env.DSH_MDNS_VER || 'unknown'
const NEEDS_PIN = (argOf('--no-pin') === undefined)

/** 主机名要能当 DNS label 用：只留 [a-z0-9-]（非 ASCII 主机名会出问题） */
const hostLabel = (() => {
  const raw = (os.hostname() || 'dsh-host').toLowerCase()
  const ascii = raw.replace(/[^a-z0-9-]/g, '-').replace(/^-+|-+$/g, '').slice(0, 40)
  return ascii || 'dsh-host'
})()
const HOST_FQDN = `${hostLabel}.local`
const INSTANCE = process.env.DSH_MDNS_NAME || argOf('--name') || `dsh-${hostLabel}`
const INSTANCE_FQDN = `${INSTANCE}.${SERVICE}`

/* ───────────────── DNS 报文编码 ───────────────── */

function encName(name) {
  const bufs = []
  for (const part of name.split('.').filter(p => p.length > 0)) {
    const b = Buffer.from(part, 'utf8')
    bufs.push(Buffer.from([b.length]), b)
  }
  bufs.push(Buffer.from([0]))
  return Buffer.concat(bufs)
}

/** 一条资源记录；flush=true 表示唯一记录（置 cache-flush 位） */
function rr(name, type, rdata, { flush = true } = {}) {
  const head = Buffer.alloc(10)
  head.writeUInt16BE(type, 0)
  head.writeUInt16BE(flush ? 0x8001 : 0x0001, 2) // IN(+cache-flush)
  head.writeUInt32BE(TTL, 4)
  head.writeUInt16BE(rdata.length, 8)
  return Buffer.concat([encName(name), head, rdata])
}

const rdataPtr = target => encName(target)
function rdataSrv(port, target) {
  const b = Buffer.alloc(6)
  b.writeUInt16BE(0, 0)     // priority
  b.writeUInt16BE(0, 2)     // weight
  b.writeUInt16BE(port, 4)  // port ← 客户端从这里取
  return Buffer.concat([b, encName(target)])
}
function rdataTxt(pairs) {
  const bufs = []
  for (const s of pairs) {
    const b = Buffer.from(s, 'utf8')
    bufs.push(Buffer.from([Math.min(255, b.length)]), b.subarray(0, 255))
  }
  return Buffer.concat(bufs)
}
const rdataA = ip => Buffer.from(ip.split('.').map(x => Number(x) & 0xff))

/* ───────────────── DNS 报文解析 ───────────────── */

/** 解析域名（支持压缩指针）→ { name, next } */
function parseName(buf, off) {
  const labels = []
  let pos = off
  let next = -1
  let hops = 0
  while (pos < buf.length && hops < 32) {
    const len = buf[pos]
    if (len === 0) { pos += 1; break }
    if ((len & 0xc0) === 0xc0) {
      if (next < 0) next = pos + 2
      pos = ((len & 0x3f) << 8) | buf[pos + 1]
      hops += 1
      continue
    }
    labels.push(buf.subarray(pos + 1, pos + 1 + len).toString('utf8'))
    pos += 1 + len
  }
  return { name: labels.join('.').toLowerCase(), next: next < 0 ? pos : next }
}

function parseQuestions(buf, qdcount) {
  const out = []
  let off = 12
  for (let i = 0; i < qdcount && off < buf.length; i++) {
    const start = off
    const { name, next } = parseName(buf, off)
    off = next
    if (off + 4 > buf.length) break
    const type = buf.readUInt16BE(off)
    off += 4
    out.push({ name, type, raw: buf.subarray(start, off) })
  }
  return out
}

/* ───────────────── 构造应答 ───────────────── */

const TYPE = { A: 1, PTR: 12, TXT: 16, SRV: 33 }

function buildReply(questions, localIp) {
  const answers = []
  const echo = []
  let ptrHit = false

  for (const { name, type, raw } of questions) {
    let matched = false
    if (name === SERVICE && type === TYPE.PTR) {
      answers.push(rr(SERVICE, TYPE.PTR, rdataPtr(INSTANCE_FQDN), { flush: false }))
      matched = true
      ptrHit = true
    }
    if (name === INSTANCE_FQDN && (type === TYPE.SRV || type === TYPE.TXT || type === 255)) {
      if (type === TYPE.SRV || type === 255) answers.push(rr(INSTANCE_FQDN, TYPE.SRV, rdataSrv(ADVERT_PORT, HOST_FQDN)))
      if (type === TYPE.TXT || type === 255) answers.push(rr(INSTANCE_FQDN, TYPE.TXT, rdataTxt(txtPairs())))
      matched = true
    }
    if (name === HOST_FQDN && (type === TYPE.A || type === 255)) {
      answers.push(rr(HOST_FQDN, TYPE.A, rdataA(localIp)))
      matched = true
    }
    if (matched) echo.push(raw)
  }
  if (answers.length === 0) return null

  // 要点 2：PTR 命中时把 SRV+TXT+A 一起放进 additional —— 一轮完成解析
  const extra = []
  if (ptrHit) {
    extra.push(rr(INSTANCE_FQDN, TYPE.SRV, rdataSrv(ADVERT_PORT, HOST_FQDN)))
    extra.push(rr(INSTANCE_FQDN, TYPE.TXT, rdataTxt(txtPairs())))
    extra.push(rr(HOST_FQDN, TYPE.A, rdataA(localIp)))
  }

  const header = Buffer.alloc(12)
  header.writeUInt16BE(0, 0)              // mDNS 多播应答 id = 0
  header.writeUInt16BE(0x8400, 2)         // QR=1 + AA=1
  header.writeUInt16BE(echo.length, 4)    // 回显问题段
  header.writeUInt16BE(answers.length, 6)
  header.writeUInt16BE(0, 8)
  header.writeUInt16BE(extra.length, 10)
  return Buffer.concat([header, ...echo, ...answers, ...extra])
}

function txtPairs() {
  const pairs = [`ver=${VER}`]
  if (NEEDS_PIN) pairs.push('pin=1')
  return pairs
}

/* ───────────────── 每网卡一个 socket ───────────────── */

function ipv4Interfaces() {
  const out = []
  for (const list of Object.values(os.networkInterfaces())) {
    for (const ni of list ?? []) {
      if (ni.family === 'IPv4' && !ni.internal) out.push(ni.address)
    }
  }
  return out
}

const sockets = []
let totalQueries = 0
let totalReplies = 0

function startOn(ip) {
  const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true })

  sock.on('error', (err) => {
    console.error(`[mdns] socket ${ip} 出错: ${err.message}`)
    try { sock.close() } catch { /* ignore */ }
  })

  sock.on('message', (msg, rinfo) => {
    if (msg.length < 12) return
    // 要点 1：QR 位（字节 2 最高位）= 1 → 这是应答，直接丢。不过滤会自环放大。
    if ((msg[2] & 0x80) !== 0) return
    totalQueries += 1
    const qdcount = msg.readUInt16BE(4)
    if (qdcount === 0) return
    const questions = parseQuestions(msg, qdcount)
    const reply = buildReply(questions, ip)
    if (reply === null) return
    // 传统单播查询（源端口 ≠ 5353）→ 单播回；否则多播回
    if (rinfo.port !== MDNS_PORT) {
      sock.send(reply, 0, reply.length, rinfo.port, rinfo.address, () => {})
    } else {
      sock.send(reply, 0, reply.length, MDNS_PORT, MDNS_ADDR, () => {})
    }
    totalReplies += 1
    if (!QUIET) {
      const names = questions.map(q => q.name).join(', ')
      console.log(`[mdns] 应答 ← ${rinfo.address} 问[${names}] 用本网卡 ${ip} 报 ${ADVERT_PORT}`)
    }
  })

  sock.bind(MDNS_PORT, () => {
    try {
      sock.addMembership(MDNS_ADDR, ip)
      console.log(`[mdns] 已在 ${ip} 加入组播 ${MDNS_ADDR}:${MDNS_PORT}`)
    } catch (e) {
      console.error(`[mdns] ${ip} 加入组播失败: ${e.message}`)
    }
  })

  sockets.push(sock)
}

const ifaces = ipv4Interfaces()
if (ifaces.length === 0) {
  console.error('[mdns] 没找到任何非回环 IPv4 网卡，退出')
  process.exit(1)
}

/** pidfile：便于精确停止；⛔ 别用「命令行含本文件名」去匹配进程（会把包装/宿主进程也算进去） */
const PIDFILE = process.env.DSH_MDNS_PIDFILE || fileURLToPath(new URL('./mdns-announce.pid', import.meta.url))
try {
  writeFileSync(PIDFILE, String(process.pid), 'utf8')
  console.log(`[mdns] pidfile: ${PIDFILE} (pid ${process.pid})`)
} catch (e) {
  console.error(`[mdns] pidfile 写入失败: ${e.message}`)
}

console.log(`[mdns] 广播 ${INSTANCE_FQDN} → ${HOST_FQDN} · 端口 ${ADVERT_PORT} · ver=${VER}${NEEDS_PIN ? ' · pin=1' : ''}`)
for (const ip of ifaces) startOn(ip)

const shutdown = () => {
  console.log(`\n[mdns] 退出（收到查询 ${totalQueries} · 发出应答 ${totalReplies}）`)
  for (const s of sockets) { try { s.close() } catch { /* ignore */ } }
  try { unlinkSync(PIDFILE) } catch { /* ignore */ }
  process.exit(0)
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
