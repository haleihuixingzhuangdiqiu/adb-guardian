# ADB 守护

给固定放置、持续供电的 Android 测试机使用的本地工具。Android 11 及以上；当前构建目标 Android 16。手机 App 尝试恢复系统无线调试开关，电脑伴随工具发现变化后的连接端口并重连已登记设备。

这是免 root 的尽力恢复方案，需要一次性授权。保留 Android 的网络信任、电脑配对和 TLS；不开放明文 5555，不读取配对码、ADB 私钥、账号或其他 App 数据。App 未声明联网权限，没有云端服务。

## 首次配置

1. 安装交付的 `ADB-Guardian-0.1.0.apk`。这是本地 debug 签名测试版，保留此项目与本机签名，便于后续覆盖更新。
2. 手机和电脑接入可互通的局域网。在系统开发者选项中打开无线调试，允许当前网络并勾选“始终允许”。
3. 用 Android Studio 的 Wi-Fi 配对，或运行 `adb pair IP:配对端口`，按终端提示输入手机显示的验证码。配对端口不是连接端口；不要把验证码写进脚本或聊天。
4. 用 `adb devices -l` 找到目标手机的完整标识，执行下方激活命令。已经无线连上的手机不需要 USB；也可用同一根 USB 数据线逐台完成授权。标识含空格时必须保留引号。

```bash
python3 desktop/adb_guardian.py activate --serial "DEVICE_SERIAL"
```

激活只给指定设备中本 App 授予 `android.permission.WRITE_SECURE_SETTINGS`，并在 `~/.config/adb-guardian/devices.json` 登记公开设备标识。若系统拒绝授权，会明确报错，不代表已成功。也可手动授权：

```bash
adb -s "DEVICE_SERIAL" shell pm grant dev.local.adbguardian android.permission.WRITE_SECURE_SETTINGS
```

5. 回到手机 App，允许通知并点“开始守护”。iQOO / vivo 需检查系统是否允许本 App 自启动、后台运行；具体入口依 OriginOS 版本而异。
6. 每台手机分别激活一次，然后启动电脑守护：

```bash
python3 desktop/adb_guardian.py watch
```

macOS 如需退出终端后继续运行、登录后自动启动，显式执行：

```bash
python3 desktop/macos_service.py install
python3 desktop/macos_service.py status
# 停止电脑守护并取消其自启动（保留配置、日志和手机设置）：
python3 desktop/macos_service.py remove
```

安装器把脚本复制到 `~/Library/Application Support/ADBGuardian/`，只安装当前用户的 `dev.local.adbguardian.watch` LaunchAgent，不修改系统 ADB 设置。日志位于 `~/Library/Logs/ADBGuardian/guardian.log`，单份最多 512 KiB，保留两份历史。电脑需要保持唤醒；电脑睡眠期间不能维持连接，唤醒后继续。此项目不会自动替用户安装该服务。

所有电脑命令都支持 `--adb /绝对路径/adb`；`activate`、`watch` 和 macOS 安装器均支持 `--config /绝对路径/devices.json`。同一批设备使用同一套配置和同一用户的 ADB 授权。

## 行为与边界

- App 目标检查间隔为 30 秒；Android 调度和厂商限制可能延迟。服务带持续通知和停止入口，只有用户启用后才尝试开机恢复。
- 系统拒绝恢复或回写关闭时，暂停自动写入并显示“需要确认系统设置”。处理系统确认后点“确认后重试”，不会反复点击或绕过信任确认。
- “守护运行中”只表示本地守护运行、无线调试开关已开启。没有声称电脑在线、端口可达或测试会话完整。
- “充电时加强保活”只在守护有条件运行且接电时持有有超时的 CPU 唤醒锁，屏幕可以熄灭。拔下充电器或停止守护时释放；会影响功耗/温度，不适合电池和后台行为测试。
- 停止守护取消自动恢复，保留现有无线调试设置；如需关闭 ADB，请再从系统无线调试中关闭开关。强行停止 App 后通常要手动重新打开；普通开机广播不保证首次解锁前运行。
- 电脑只操作登记设备，区分连接与配对端口，每次通过 shell 校验真实序列号。mDNS 名称碰撞的 `(2)` 后缀可识别。设备独立退避，不全局 kill-server、不自动重启手机。失效配对需要人工恢复。
- 瞬断仍会中断 shell、安装或测试；本工具不自动重放业务操作，不重建调用方自己的 forward/reverse。
- iQOO / vivo 的 WRITE_SECURE_SETTINGS、隐藏设置键、后台限制和长期稳定性必须实机确认。即使界面开关为开，也可能有网络隔离、端口监听或授权问题；不能据此宣称“永不断线”。

## 开发与验证

独立于 ICard，无业务接口、登录流程或发布上传。Gradle 构建必须使用本机资源预算入口：

```bash
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  /Users/lgj/.codex/scripts/with-gradle-budget.sh -- \
  ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --max-workers=4
python3 -m unittest discover -s desktop -t .
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。验证原始证据保存在忽略的 `build/mobile-regression/`；不将真实设备配置、密钥或原始日志提交。Maestro 用例不使用默认 `launchApp`，因为它会自动授予开发权限，导致权限边界测试失真。运行前通过 ADB 启动本 App，并明确准备对应权限/系统确认前置状态。

依据：[Android 无线调试](https://developer.android.com/tools/adb)、[AOSP 网络信任检查](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/adb/AdbDebuggingManager.java)、[前台 connectedDevice 服务](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)。
