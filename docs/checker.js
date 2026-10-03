/*
 * PaperProxy compatibility checker.
 * Runs entirely in the browser: the jar is read locally and never uploaded.
 * Copyright (C) 2026 PaperProxy Contributors, GPL-3.0.
 */
(function (root) {
  'use strict';

  const MAX_JAVA = 25; // PaperProxy runs on Java 25
  const JAVA_BY_MAJOR = (major) => major - 44;

  // References into BungeeCord internals that PaperProxy's compatibility layer cannot offer.
  const BUNGEE_INTERNALS = [
    { prefix: 'net/md_5/bungee/protocol/', level: 'warn',
      text: 'Uses BungeeCord packet classes. Sending raw packets does not work on PaperProxy.' },
    { prefix: 'net/md_5/bungee/connection/', level: 'fail',
      text: 'Uses BungeeCord connection internals, which do not exist on PaperProxy.' },
    { prefix: 'net/md_5/bungee/netty/', level: 'fail',
      text: 'Hooks into BungeeCord’s Netty pipeline, which does not exist on PaperProxy.' },
    { prefix: 'net/md_5/bungee/BungeeCord', level: 'fail',
      text: 'Uses the BungeeCord implementation class directly instead of the API.' },
    { prefix: 'net/md_5/bungee/UserConnection', level: 'fail',
      text: 'Uses BungeeCord’s internal player class.' },
    { prefix: 'net/md_5/bungee/ServerConnection', level: 'fail',
      text: 'Uses BungeeCord’s internal server connection class.' },
    { prefix: 'net/md_5/bungee/api/score/', level: 'warn',
      text: 'Uses proxy side scoreboards. They are ignored on PaperProxy.' },
    { prefix: 'net/md_5/bungee/api/dialog/', level: 'warn',
      text: 'Uses dialogs. They are not supported yet and are ignored.' },
    { prefix: 'net/md_5/bungee/api/ReconnectHandler', level: 'warn',
      text: 'Sets a ReconnectHandler. PaperProxy chooses servers itself; the handler is ignored.' },
  ];
  const BUNGEE_METHODS = [
    { name: 'sendPacket', level: 'warn',
      text: 'Calls sendPacket (raw packets). This throws on PaperProxy.' },
    { name: 'sendPacketQueued', level: 'warn',
      text: 'Calls sendPacketQueued (raw packets). This throws on PaperProxy.' },
  ];

  /** Reads the constant pool of a class file. Returns null for broken files. */
  function readClass(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (bytes.length < 10 || view.getUint32(0) !== 0xCAFEBABE) return null;
    const major = view.getUint16(6);
    const count = view.getUint16(8);
    const utf8 = new Array(count);
    const classIndex = new Array(count);
    let pos = 10;
    const decoder = new TextDecoder('utf-8');
    for (let i = 1; i < count; i++) {
      const tag = bytes[pos++];
      switch (tag) {
        case 1: { const len = view.getUint16(pos); pos += 2;
          utf8[i] = decoder.decode(bytes.subarray(pos, pos + len)); pos += len; break; }
        case 7: classIndex[i] = view.getUint16(pos); pos += 2; break;
        case 8: case 16: case 19: case 20: pos += 2; break;
        case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: pos += 4; break;
        case 15: pos += 3; break;
        case 5: case 6: pos += 8; i++; break;
        default: return null;
      }
    }
    pos += 2; // access flags
    const thisClass = utf8[classIndex[view.getUint16(pos)]];
    const superIdx = view.getUint16(pos + 2);
    const superClass = superIdx ? utf8[classIndex[superIdx]] : null;
    return { major, strings: utf8.filter(Boolean), thisClass, superClass };
  }

  function parseYaml(text) {
    // Only the simple top-level keys we need (name, main, version, libraries present).
    const out = {};
    for (const line of text.split(/\r?\n/)) {
      const m = /^([A-Za-z-]+):\s*(.*)$/.exec(line);
      if (m) out[m[1]] = m[2].replace(/^['"]|['"]$/g, '').trim();
    }
    return out;
  }

  /**
   * Analyses a plugin jar.
   * @param {JSZip} JSZip the JSZip constructor
   * @param {ArrayBuffer|Uint8Array} data the jar
   * @returns {Promise<object>} the report
   */
  async function analyse(JSZip, data) {
    const zip = await JSZip.loadAsync(data);
    const report = { name: null, version: null, type: 'unknown', verdict: 'ok', findings: [],
      javaRequired: null, classes: 0 };
    const add = (level, text, where) => {
      let f = report.findings.find(x => x.text === text);
      if (!f) { f = { level, text, where: [] }; report.findings.push(f); }
      if (where && f.where.length < 5 && !f.where.includes(where)) f.where.push(where);
    };

    const velocityJson = zip.file('velocity-plugin.json');
    const bungeeYml = zip.file('bungee.yml');
    const pluginYml = zip.file('plugin.yml');
    const paperYml = zip.file('paper-plugin.yml');
    let mainClass = null;

    if (velocityJson) {
      const json = JSON.parse(await velocityJson.async('string'));
      report.name = json.name || json.id; report.version = json.version || null;
      report.type = (bungeeYml || pluginYml) ? 'universal' : 'velocity';
    } else if (bungeeYml || pluginYml) {
      const yml = parseYaml(await (bungeeYml || pluginYml).async('string'));
      report.name = yml.name || null; report.version = yml.version || null;
      mainClass = yml.main ? yml.main.replace(/\./g, '/') : null;
      report.type = 'bungee-candidate';
      if (yml.libraries !== undefined) {
        add('info', 'Declares libraries in bungee.yml. PaperProxy downloads them like BungeeCord.');
      }
    } else if (paperYml) {
      report.type = 'bukkit';
    }

    let maxMajor = 0;
    let usesBungee = false;
    const entries = Object.values(zip.files).filter(f => !f.dir && f.name.endsWith('.class')
      && !f.name.startsWith('META-INF/versions/'));
    for (const entry of entries) {
      const parsed = readClass(await entry.async('uint8array'));
      if (!parsed) continue;
      report.classes++;
      maxMajor = Math.max(maxMajor, parsed.major);
      const where = parsed.thisClass ? parsed.thisClass.replace(/\//g, '.') : entry.name;
      if (mainClass && parsed.thisClass === mainClass) {
        if (parsed.superClass === 'org/bukkit/plugin/java/JavaPlugin') report.type = 'bukkit';
        else if (parsed.superClass === 'net/md_5/bungee/api/plugin/Plugin') report.type = 'bungee';
      }
      for (const raw of parsed.strings) {
        // Reflection uses dotted names ("net.md_5.bungee.BungeeCord").
        const s = raw.includes('net.md_5.bungee.') ? raw.replace(/\./g, '/') : raw;
        if (s.startsWith('net/md_5/bungee/') || s.includes('Lnet/md_5/bungee/')) usesBungee = true;
        for (const rule of BUNGEE_INTERNALS) {
          if (s.startsWith(rule.prefix) || s.includes('L' + rule.prefix)) {
            add(rule.level, rule.text, where);
          }
        }
      }
      // Only classes that really use BungeeCord's packet access count; method names alone are
      // too common (database drivers have sendPacket too).
      if (parsed.strings.some(x => x.includes('net/md_5/bungee/api/connection/Connection$Unsafe'))) {
        for (const rule of BUNGEE_METHODS) {
          if (parsed.strings.includes(rule.name)) add(rule.level, rule.text, where);
        }
      }
    }

    if (report.type === 'bungee-candidate') report.type = usesBungee ? 'bungee' : 'unknown';
    if (maxMajor) {
      report.javaRequired = JAVA_BY_MAJOR(maxMajor);
      if (report.javaRequired > MAX_JAVA) {
        add('fail', 'Compiled for Java ' + report.javaRequired + '. PaperProxy runs on Java '
          + MAX_JAVA + '.');
      }
    }

    switch (report.type) {
      case 'velocity':
        add('ok', 'Velocity plugin. Runs natively on PaperProxy.');
        // Internals of Velocity are still there in PaperProxy, so they are fine.
        report.findings = report.findings.filter(f => !f.text.startsWith('Uses BungeeCord')
          && !f.text.startsWith('Calls send') && !f.text.includes('scoreboard')
          && !f.text.includes('dialog') && !f.text.includes('ReconnectHandler'));
        break;
      case 'universal':
        add('ok', 'Contains a Velocity and a BungeeCord version. PaperProxy uses the Velocity one.');
        report.findings = report.findings.filter(f => f.level === 'ok' || f.level === 'info'
          || f.text.startsWith('Compiled'));
        break;
      case 'bungee':
        add('ok', 'BungeeCord plugin. Runs through PaperProxy’s BungeeCord layer.');
        break;
      case 'bukkit':
        report.findings = [];
        add('fail', 'This is a Bukkit/Spigot/Paper plugin for game servers, not for a proxy. '
          + 'Install it on your backend servers instead.');
        break;
      default:
        add('fail', 'No velocity-plugin.json, bungee.yml or plugin.yml found. This does not look '
          + 'like a proxy plugin.');
    }

    if (report.findings.some(f => f.level === 'fail')) report.verdict = 'fail';
    else if (report.findings.some(f => f.level === 'warn')) report.verdict = 'warn';
    const order = { fail: 0, warn: 1, info: 2, ok: 3 };
    report.findings.sort((a, b) => order[a.level] - order[b.level]);
    return report;
  }

  const api = { analyse };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.PaperProxyChecker = api;
})(typeof window !== 'undefined' ? window : globalThis);
