'use strict';
'require view';
'require poll';
'require rpc';
'require uci';

const callStatus = rpc.declare({
	object: 'tvboxcheck',
	method: 'status',
	expect: {}
});

const callStart = rpc.declare({
	object: 'tvboxcheck',
	method: 'start',
	args: { limit: 0, full: 0 },
	expect: {}
});

const callStop = rpc.declare({
	object: 'tvboxcheck',
	method: 'stop',
	expect: {}
});

const callResults = rpc.declare({
	object: 'tvboxcheck',
	method: 'get_results',
	expect: {}
});

const callLog = rpc.declare({
	object: 'tvboxcheck',
	method: 'get_log',
	expect: {}
});

const STATUS_COLOR = {
	healthy: '#2e7d32',
	degraded: '#e65100',
	unknown: '#616161',
	dead: '#c62828',
	untestable: '#9e9e9e'
};

const STATUS_LABEL = {
	healthy: '可播',
	degraded: '部分可用',
	unknown: '未确认',
	dead: '判死',
	untestable: '不测'
};

return view.extend({
	load: function() {
		return Promise.all([
			uci.load('tvboxcheck'),
			callStatus(),
			callResults()
		]);
	},

	render: function(data) {
		let self = this;
		let status = data[1] || {};
		let results = data[2] || { checks: {}, tally: {} };

		let state = {
			showAll: false,
			running: !!status.running
		};

		const badge = E('b', {});
		const progressText = E('span', { style: 'font-variant-numeric:tabular-nums' }, '—');
		const tallyBox = E('span', {});
		const tableBox = E('div', {});
		const logBox = E('pre', {
			style: 'max-height:320px;overflow:auto;background:#111;color:#ddd;padding:8px;white-space:pre-wrap;'
		}, '(无日志)');

		const filterCheck = E('input', { type: 'checkbox', id: 'tvbc-showall' });
		filterCheck.checked = false;
		filterCheck.addEventListener('change', function() {
			state.showAll = filterCheck.checked;
			renderTable();
		});

		function renderTally() {
			let tally = results.tally || {};
			let order = [ 'healthy', 'degraded', 'unknown', 'dead', 'untestable' ];
			let nodes = [];

			for (let i = 0; i < order.length; i++) {
				let key = order[i];
				let n = tally[key] || 0;

				nodes.push(E('span', {
					style: 'display:inline-block;margin-right:14px;'
				}, [
					E('b', { style: 'color:%s'.format(STATUS_COLOR[key]) },
						(STATUS_LABEL[key]) + ' '),
					E('span', {}, String(n))
				]));
			}
			tallyBox.replaceChildren(...nodes);
		}

		function renderTable() {
			let checks = results.checks || {};
			let rows = [];

			for (let key in checks) {
				let rec = checks[key] || {};

				if (!state.showAll && (rec.status === 'untestable'))
					continue;

				rows.push(E('tr', {}, [
					E('td', { style: 'word-break:break-all;max-width:280px;' }, key),
					E('td', {}, [
						E('b', { style: 'color:%s'.format(STATUS_COLOR[rec.status] || '#000') },
							STATUS_LABEL[rec.status] || rec.status || '?')
					]),
					E('td', {}, String(rec.level != null ? rec.level : '-')),
					E('td', { style: 'word-break:break-all;max-width:320px;' },
						rec.reason || ''),
					E('td', {}, rec.ttfb_ms != null ? String(rec.ttfb_ms) : '-'),
					E('td', {}, rec.play_kbps != null ? String(rec.play_kbps) : '-'),
					E('td', {}, String(rec.fail_streak != null ? rec.fail_streak : 0)),
					E('td', {}, rec.last_check || '-')
				]));
			}

			tableBox.replaceChildren(
				E('table', { 'class': 'table cbi-section-table' }, [
					E('tr', { 'class': 'tr table-titles' }, [
						E('th', { 'class': 'th' }, '站点'),
						E('th', { 'class': 'th' }, '状态'),
						E('th', { 'class': 'th' }, '层'),
						E('th', { 'class': 'th' }, '原因'),
						E('th', { 'class': 'th' }, '首字节ms'),
						E('th', { 'class': 'th' }, '速度KB/s'),
						E('th', { 'class': 'th' }, '连败'),
						E('th', { 'class': 'th' }, '最近检测')
					]),
					...rows
				]),
				E('p', { 'class': 'muted' }, '共显示 %d 条'.format(rows.length))
			);
		}

		function updateStatus(st) {
			st = st || {};
			state.running = !!st.running;
			badge.replaceChildren(E('b', {
				style: 'color:%s'.format(st.running ? '#2e7d32' : '#616161')
			}, st.running ? '运行中' : '空闲'));

			let p = st.progress || {};
			progressText.textContent = (p.total > 0)
				? ('%d / %d'.format(p.done || 0, p.total)) : '—';
			startButton.disabled = st.running;
			stopButton.disabled = !st.running;
		}

		const startButton = E('button', {
			type: 'button',
			'class': 'btn cbi-button cbi-button-apply'
		}, '开始探测');
		startButton.addEventListener('click', function() {
			startButton.disabled = true;
			return callStart({ limit: 0, full: 1 }).then(function() {
				return callStatus().then(updateStatus);
			}).catch(function() {
				startButton.disabled = false;
			});
		});

		const startSampleButton = E('button', {
			type: 'button',
			'class': 'btn cbi-button cbi-button-action'
		}, '抽测 20 站');
		startSampleButton.addEventListener('click', function() {
			startSampleButton.disabled = true;
			return callStart({ limit: 20, full: 1 }).then(function() {
				return callStatus().then(updateStatus);
			}).catch(function() {
				startSampleButton.disabled = false;
			});
		});

		const stopButton = E('button', {
			type: 'button',
			'class': 'btn cbi-button cbi-button-reset'
		}, '停止');
		stopButton.addEventListener('click', function() {
			return callStop().then(function() {
				return callStatus().then(updateStatus);
			});
		});

		const refreshButton = E('button', {
			type: 'button',
			'class': 'btn cbi-button'
		}, '刷新结果');
		refreshButton.addEventListener('click', function() {
			return callResults().then(function(r) {
				results = r || { checks: {}, tally: {} };
				renderTally();
				renderTable();
			});
		});

		const logButton = E('button', {
			type: 'button',
			'class': 'btn cbi-button'
		}, '刷新日志');
		logButton.addEventListener('click', function() {
			return callLog().then(function(r) {
				logBox.textContent = (r && r.content) ? r.content : '(无日志)';
			});
		});

		updateStatus(status);
		renderTally();
		renderTable();

		callLog().then(function(r) {
			if (r && r.content)
				logBox.textContent = r.content;
		}).catch(function() {});

		// 运行中每 3 秒轮询状态；跑完自动刷新一次结果
		let wasRunning = state.running;
		poll.add(function() {
			return callStatus().then(function(st) {
				updateStatus(st);
				let running = !!(st && st.running);

				if (wasRunning && !running)
					return callResults().then(function(r) {
						results = r || results;
						renderTally();
						renderTable();
					});
				wasRunning = running;
			}).catch(function() {});
		}, 3);

		return E([], [
			E('h2', {}, 'TVBox 源检测'),
			E('div', { 'class': 'cbi-section' }, [
				E('p', {}, [
					E('strong', {}, '状态：'), badge, '  ',
					E('strong', {}, '进度：'), progressText
				]),
				E('div', { style: 'margin:8px 0;' }, [
					startButton, ' ', startSampleButton, ' ', stopButton, ' ',
					refreshButton, ' ', logButton, ' ',
					E('label', { style: 'margin-left:12px;' }, [ filterCheck,
						' 显示 untestable（%d 个 type3/不可播源）'.format(
							(results.tally && results.tally.untestable) || 0) ])
				]),
				E('p', {}, [ E('strong', {}, '汇总：'), tallyBox ]),
				E('div', { style: 'max-height:600px;overflow:auto;' }, [ tableBox ]),
				E('h3', {}, '运行日志'),
				logBox
			])
		]);
	},

	handleSaveApply: null,
	handleSave: null,
	handleReset: null
});
