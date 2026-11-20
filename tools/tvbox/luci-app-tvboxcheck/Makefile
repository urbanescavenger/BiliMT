# luci-app-tvboxcheck —— TVBox 源试播探测（BiliMT 仓库维护，设计见
# docs/tvbox-source-aggregator-design.md §5）。
# 引擎是纯标准库 Python（root/usr/bin/tvboxcheck），依赖照 luci-app-cfpick
# 的 python3 子包先例，另加 ca-bundle（https CA 证书）。

include $(TOPDIR)/rules.mk

PKG_NAME:=luci-app-tvboxcheck
LUCI_TITLE:=TVBox source health checker (trial-play probe)
LUCI_DEPENDS:=+python3-light +python3-urllib +python3-codecs +python3-openssl +ca-bundle
LUCI_PKGARCH:=all
PKG_VERSION:=0.1.0
PKG_RELEASE:=0
PKG_LICENSE:=MIT
PKG_MAINTAINER:=https://github.com/urbanescavenger

define Package/$(PKG_NAME)/conffiles
/etc/config/tvboxcheck
endef

# rpcd 只在启动时加载 /usr/share/rpcd/ucode/*，升级后不重载会继续跑旧插件
# （luci-app-cfpick 踩过的坑）。
define Package/$(PKG_NAME)/postinst
#!/bin/sh
[ -n "$${IPKG_INSTROOT}" ] && exit 0
/etc/init.d/rpcd reload 2>/dev/null || /etc/init.d/rpcd restart 2>/dev/null
exit 0
endef

include $(TOPDIR)/feeds/luci/luci.mk

# call BuildPackage - OpenWrt buildroot signature
