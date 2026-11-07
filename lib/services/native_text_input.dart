import 'package:flutter/services.dart';

/// 原生文本输入桥。
///
/// 手表（窄屏）上 Flutter 自己的 TextField 调不出系统输入法：IME 窗口建好了、
/// `mInputShown=true`，但 `mDrawState` 卡在 READY_TO_SHOW、`shown=false`，永远
/// 不出首帧。原生 EditText 不受影响，所以窄屏改走这里——由原生浮层承载输入，
/// 系统输入法（搜狗手表版）就能正常弹出、正常打中文。
///
/// 桌面/手机不经过这里，仍用普通 TextField。
class NativeTextInput {
  const NativeTextInput._();

  static const MethodChannel _channel = MethodChannel('fqapp/native_input');

  /// 打开原生输入浮层。返回用户确定的文本；取消或通道不可用时返回 null。
  static Future<String?> open({
    String initial = '',
    String hint = '',
  }) async {
    try {
      return await _channel.invokeMethod<String>('open', {
        'initial': initial,
        'hint': hint,
      });
    } on MissingPluginException {
      // 非 Android 构建（或有测试桩）时静默退化为原来的输入框。
      return null;
    } on PlatformException {
      return null;
    }
  }
}
