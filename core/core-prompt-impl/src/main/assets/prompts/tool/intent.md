---
id: tool.intent
version: 1
vars: [mapList]
meta:
  param.kind: 事务类型
  param.hour: alarm：时，0–23
  param.minute: alarm：分，0–59，省略按 0
  param.days: alarm：重复日，1 到 7 对应周一到周日；省略表示只响一次
  param.seconds: timer：秒数
  param.message: alarm、timer 的标签
  param.title: calendar：日程标题
  param.begin: calendar：开始时间，如 2026-10-24T19:00（手机时区）；全天日程只写日期 2026-10-24
  param.end: calendar：结束时间，格式同 begin；省略则按默认时长
  param.all_day: calendar：是否全天
  param.location: calendar：地点
  param.description: calendar：备注
  param.number: dial、sms_draft：电话号码
  param.text: sms_draft、share：文字内容
  param.app: share：目标应用（包名或名称，省略则弹系统分享面板）；settings 的 app_notifications 页必填
  param.destination: navigate：目的地名称或地址，优先使用
  param.lat: navigate：目的地纬度，中国大陆用 GCJ-02，其他地区用 WGS-84
  param.lng: navigate：目的地经度，坐标系同 lat
  param.mode: navigate：出行方式，默认 drive
  param.map: navigate：指定地图应用；省略则按用户设置选择
  param.page: settings：要打开的设置页
---
用系统意图直接办事：闹钟、计时器、日程、拨号、短信草稿、分享、导航、打开设置页这几类事务，用本工具，不要通过点界面去完成。
各 kind 的字段：alarm(hour, minute?, days?, message?)；timer(seconds, message?)；calendar(title, begin, end?, all_day?, location?, description?)；dial(number)；sms_draft(text, number?)；share(text, app?)；navigate(destination 或 lat+lng, mode?, map?)；settings(page, app?)。
本工具只是把事务交给系统或对应应用，后面的界面和确认由对方决定：日程、短信草稿只是预填，要在界面上点保存或发送（用 observe 和 tap 继续）；闹钟是否生效以时钟应用里看到的为准。启动成功不证明事务已经办成，不要连续重发。工具不会替用户发送短信或拨出电话。
navigate：没有指定 map 时按用户设置选择地图应用；用户明确说了用哪个地图，就传对应的 map；目的地在中国大陆以外且已安装 Google 地图时，可以传 map=google。可用的 map：{{mapList}}。优先传 destination，只有确实知道坐标时才传 lat 和 lng。
settings：不提供无障碍、开发者选项、权限、设备管理器等安全相关页面。
