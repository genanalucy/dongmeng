# Android Core Translation UI Redesign Device Acceptance

## Task 3 Face-to-Face Required Device Validation

- 手动模式：按住左、右麦克风时持续收音；按住期间不得提前 release；真实抬手与系统/手势取消各只触发一次 release。
- 连续模式：启动后左耳持续收音；右耳按住仅临时切换；抬手或取消后恢复左耳。
- TalkBack、Switch Access 和键盘：左右麦克风的中文 action 可执行；无障碍瞬时 action 不重复触发 press/release。
- 麦克风权限：首次请求、拒绝、已授权和请求期间抬手的时序不会产生错配的 press/release。
- 检查聊天最新消息位于底部操作安全区上方，历史阅读时不被强制滚动。

## Replay Deferral

当前行为权威层未提供受控 per-turn replay API。Task 3 不显示重播按钮，且不修改 `FaceToFaceViewModel`、`FaceToFaceCoordinator` 或音频管线。待提供受控 API 后单独验收重播和另一耳路由。
