'use strict';

// rpcd ucode 插件：给 LuCI 状态页提供 status/start/stop/get_log/get_results。
// 探测引擎是 /usr/bin/tvboxcheck（纯标准库 Python），本插件只负责起停、
// 读日志和读结果；判据全在引擎里。结构照 luci-app-cfpick 的 cfpick.uc。
// 注意：'use strict' 下函数声明不提升，被调用者必须定义在调用者之前。

const fs = require('fs');
const uci = require('uci');

const LOG_FILE = '/tmp/tvboxcheck.log';
const PID_FILE = '/tmp/tvboxcheck.pid';
const ENGINE = '/usr/bin/tvboxcheck';
const DEFAULT_INPUT = 'https://raw.githubusercontent.com/urbanescavenger/BiliMT/main/tools/tvbox/dist/tvbox.json';
const DEFAULT_OUTPUT = '/etc/tvboxcheck/checks.json';

function command(cmd) {
	return {
		code: system(cmd)
	};
}

function shquote(value) {
	return "'" + replace("" + value, "'", "'\\''") + "'";
}

function read_file(path) {
	if (!fs.access(path, 'f'))
		return '';

	let fd = fs.open(path, 'r');
	let data = '';

	if (fd) {
		data = fd.read('all') || '';
		fd.close();
	}
	return data;
}

function uci_get(option, dflt) {
	try {
		let cur = uci.cursor();
		cur.load('tvboxcheck');
		let v = cur.get('tvboxcheck', 'global', option);
		if (v == null || v == '')
			return dflt;
		return v;
	}
	catch (e) {
		return dflt;
	}
}

function run_pid() {
	let pid = trim(read_file(PID_FILE) || '');
	return match(pid, /^[0-9]+$/) ? pid : null;
}

// pid 活着且 cmdline 确实是本引擎（防 PID 复用误判，cfpick 同款判据）。
function run_alive() {
	let pid = run_pid();
	if (!pid)
		return false;
	return command('[ -d /proc/' + pid + ' ] && grep -q "tvboxcheck" /proc/' +
		pid + '/cmdline 2>/dev/null').code == 0;
}

// 从日志尾部倒着找形如 [n/total] 的进度行。引擎每完成一站打一行。
function parse_progress(log) {
	if (log == '')
		return { done: 0, total: 0 };

	let lines = split(log, '\n');

	for (let i = length(lines) - 1; i >= 0; i--) {
		let line = lines[i];
		let a = index(line, '[');
		let b = index(line, ']');

		if (a < 0 || b <= a)
			continue;

		let pair = split(substr(line, a + 1, b - a - 1), '/');

		if (length(pair) == 2 && match(trim(pair[0]), /^[0-9]+$/) &&
		    match(trim(pair[1]), /^[0-9]+$/))
			return { done: int(trim(pair[0])), total: int(trim(pair[1])) };
	}
	return { done: 0, total: 0 };
}

// 读 checks.json 按 status 汇总计数（结果文件可能 300KB+，只做计数和透传）。
function load_checks(output) {
	let raw = read_file(output);

	if (raw == '')
		return null;

	try {
		return json(raw);
	}
	catch (e) {
		return null;
	}
}

function tally_of(checks) {
	let tally = {};

	if (checks == null)
		return tally;

	for (let key in checks) {
		let st = (checks[key] != null && checks[key].status != null)
			? checks[key].status : 'unknown';
		tally[st] = (tally[st] || 0) + 1;
	}
	return tally;
}

function status() {
	let output = uci_get('output', DEFAULT_OUTPUT);
	let running = run_alive();
	let log = read_file(LOG_FILE);

	// 日志只截尾部 32KB 解析进度，防长跑日志拖慢 ubus 调用
	if (length(log) > 32768)
		log = substr(log, length(log) - 32768);

	let checks = load_checks(output);

	return {
		running: running,
		pid: running ? run_pid() : null,
		progress: parse_progress(log),
		output: output,
		output_exists: fs.access(output, 'f'),
		tally: tally_of(checks),
		updated: (checks != null) ? checks.__updated : null
	};
}

function start(req) {
	if (run_alive())
		return { started: false, running: true };

	let input = uci_get('input', DEFAULT_INPUT);
	let output = uci_get('output', DEFAULT_OUTPUT);
	let concurrency = uci_get('concurrency', '8');
	let timeout = uci_get('timeout', '8');
	let limit = int(req != null ? req.limit : 0) || 0;

	let cmd = shquote(ENGINE) +
		' --input ' + shquote(input) +
		' --output ' + shquote(output) +
		' --concurrency ' + shquote(concurrency) +
		' --timeout ' + shquote(timeout);

	if (limit > 0)
		cmd += ' --limit ' + limit;
	if (req != null && req.full)
		cmd += ' --full';

	// 截断日志 → 后台起引擎 → 记录 pid（同一 sh 内 $! 才是引擎本身）
	command(': > ' + shquote(LOG_FILE) + '; ' + cmd + ' >> ' +
		shquote(LOG_FILE) + ' 2>&1 & echo $! > ' + shquote(PID_FILE));

	return { started: true, running: true };
}

function stop() {
	let pid = run_pid();

	if (pid != null && run_alive()) {
		command('kill ' + pid + ' 2>/dev/null');
		command('rm -f ' + shquote(PID_FILE));
		return { stopped: true };
	}
	command('rm -f ' + shquote(PID_FILE));
	return { stopped: false, message: '没有运行中的探测' };
}

function get_log() {
	let log = read_file(LOG_FILE);

	if (length(log) > 65536)
		log = substr(log, length(log) - 65536);

	return { content: log };
}

function get_results() {
	let output = uci_get('output', DEFAULT_OUTPUT);
	let checks = load_checks(output);

	return {
		output: output,
		exists: fs.access(output, 'f'),
		checks: checks != null ? checks : {},
		tally: tally_of(checks)
	};
}

return {
	'tvboxcheck': {
		status: {
			call: function() {
				return status();
			}
		},

		start: {
			args: { limit: 0, full: 0 },
			call: function(req) {
				return start(req);
			}
		},

		stop: {
			call: function() {
				return stop();
			}
		},

		get_log: {
			call: function() {
				return get_log();
			}
		},

		get_results: {
			call: function() {
				return get_results();
			}
		}
	}
};
