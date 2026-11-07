import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'home/home_design.dart';

/// 低于这个逻辑宽度就换成紧凑 dock。
///
/// 手机最窄也在 360dp 以上，只有手表/极窄分屏会落进来（OPPO Watch 3 Pro
/// 改完密度后是 320dp）。
const double compactShellDockBreakpoint = 340;

/// 窄屏（手表）下的底部导航。
///
/// 上游的玻璃胶囊 dock 是 54dp 高 + 12dp 底距，在 320×420dp 的画布上要占
/// 掉近六分之一高度，胶囊本身也会把四个入口挤成一排细缝。窄屏这里换成
/// 等分图标条：入口、顺序、选中逻辑都和 [ShellDock] 一致，去掉玻璃底板、
/// 拖动预览与滑动手势，高度降到 48dp。
class CompactShellDock extends StatelessWidget {
  const CompactShellDock({
    super.key,
    required this.selectedIndex,
    required this.onSelected,
    required this.palette,
    required this.overVideo,
  });

  final int selectedIndex;
  final ValueChanged<int> onSelected;
  final HomePalette palette;

  /// 短剧推荐频道背后是全屏视频流：底板按深色画布绘制，与 [ShellDock] 一致。
  final bool overVideo;

  static const _items = <({IconData icon, String label})>[
    (icon: CupertinoIcons.house, label: '首页'),
    (icon: CupertinoIcons.play, label: '短剧'),
    (icon: CupertinoIcons.book, label: '书架'),
    (icon: CupertinoIcons.person_crop_circle, label: '我的'),
  ];

  @override
  Widget build(BuildContext context) {
    final background = overVideo ? const Color(0xFF191919) : palette.surface;
    return Material(
      color: background,
      child: SafeArea(
        top: false,
        child: Container(
          height: 48,
          decoration: BoxDecoration(
            border: Border(
              top: BorderSide(color: palette.line, width: 0.5),
            ),
          ),
          child: Row(
            children: [
              for (var index = 0; index < _items.length; index++)
                Expanded(child: _buildItem(index)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildItem(int index) {
    final item = _items[index];
    final selected = index == selectedIndex;
    // 墨水屏下 accentText 与 muted 都是纯黑，靠字重与图标是否实心来区分。
    final color = selected ? palette.ink : palette.muted;
    return InkWell(
      onTap: () => onSelected(index),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(item.icon, size: 19, color: color),
          const SizedBox(height: 2),
          Text(
            item.label,
            maxLines: 1,
            overflow: TextOverflow.clip,
            style: TextStyle(
              fontSize: 10,
              height: 1.1,
              color: color,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
            ),
          ),
        ],
      ),
    );
  }
}
