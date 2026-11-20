'use strict';
'require view';
'require form';
'require uci';

return view.extend({
	load: function() {
		return uci.load('tvboxcheck');
	},

	render: function() {
		let m, s, o;

		m = new form.Map('tvboxcheck', 'TVBox 源检测设置',
			'探测引擎的输入与网络参数。判据（本仓库播放方法 L1-L3 试播）固化在引擎里，' +
			'此处只影响拉哪个订阅、并发与超时。结果文件默认留在本机 ' +
			'/etc/tvboxcheck/checks.json，导出回传是可选动作。');

		s = m.section(form.TypedSection, 'global', '基本设置');
		s.anonymous = true;
		s.addremove = false;

		o = s.option(form.Value, 'input', '订阅地址',
			'URL 或本地路径，默认为 BiliMT 仓库 main 分支的聚合产物');
		o.placeholder = 'https://raw.githubusercontent.com/urbanescavenger/BiliMT/main/tools/tvbox/dist/tvbox.json';
		o.rmempty = false;

		o = s.option(form.Value, 'output', '结果文件',
			'checks.json 输出路径（状态机与仓库侧 aggregate.py 对接的格式）');
		o.placeholder = '/etc/tvboxcheck/checks.json';
		o.rmempty = false;

		o = s.option(form.Value, 'concurrency', '并发数');
		o.datatype = 'uinteger';
		o.placeholder = '8';
		o.rmempty = false;

		o = s.option(form.Value, 'timeout', '单请求超时（秒）',
			'近似本仓库 TvboxRepository 的 5s 连接 / 8s 读取');
		o.datatype = 'uinteger';
		o.placeholder = '8';
		o.rmempty = false;

		return m.render();
	}
});
