> **历史资料，非现行规范或本轮验收。** 来源：integration 工作区未提交资料样本（工作区 HEAD `8e625a67ebcffe0a5776228f5ac4ba907f05391a` 不代表这些内容已提交）；采样 2026-11-16T20:25:54.938184+00:00。
> 原位置 `.scratch/auto-face-mode/spec.md`；原始 SHA-256 `d6dc605a171ef539698789505e844db707d3a914c2ed5482705b3c3013f5064a`。公开副本添加本标记，修复可定位链接，机器路径、旧环境地址/开发连接及设备名称按需脱敏，规范行尾空白；旧检查结论仅属于当时范围。正文中路径与命令为历史记录。
> 来源、处置与哈希见[本批索引](../../../../indexes/07-source-and-tools.md)；当前实现见[模块地图](../../../../../docs/architecture/README.md)。

# 面对面自动语言识别模式

Status: ready-for-agent

## Problem Statement

面对面翻译当前依赖用户手动选择发言侧或按住说话键。双方面对面交流时，用户需要在每次轮换发言时操作设备，且难以保证译文总是播放给使用目标语言的一方。用户希望只选择两种会话语言后，让产品自动判断当前发言属于哪一种语言，翻译为另一种语言，并且只把译文送到听目标语言的对方耳机；发言者自己的耳机不得听到自己的译文。

## Solution

在面对面翻译中提供自动模式。用户仍明确配置本次会话的两种候选语言 A 与 B；自动模式只在这两个候选中识别当前发言的源语言。每个被接受的发言片段形成一个翻译会话：源语言为 A 时翻译为 B，目标语音只路由到使用 B 的用户耳机；源语言为 B 时反向处理。字幕与已完成 Turn 保持可见和可保存。

自动模式对 malformed provider 输出、空最终对和已知失败路径 fail-closed：不生成译文、不播放音频、不创建已完成 Turn，并以可恢复错误结束该 provider session。Azure Unified Speech v2 的直连协议不提供本实现可用的 LID 置信度；候选之外的第三语言可能被 Azure 强制映射为 A/B，产品接受该风险且不会把它描述为静默丢弃。当前实现不检测重叠说话，因而不承诺其会静默拒绝。用户可暂停、继续或停止自动模式；停止后安全结束采集、翻译与播放，不破坏已完成 Turn。

## User Stories

1. As a 面对面翻译用户, I want to choose exactly two session languages before enabling automatic mode, so that automatic detection has a clear and bounded language set.
2. As language-A speaker, I want my A-language speech recognized automatically, so that I do not need to select my side or hold a talk button.
3. As language-B speaker, I want my B-language speech recognized automatically, so that the conversation can naturally alternate in either direction.
4. As language-A speaker, I want my accepted speech translated into B, so that the language-B speaker can understand me.
5. As language-B speaker, I want my accepted speech translated into A, so that the language-A speaker can understand me.
6. As the target-language listener, I want translated audio delivered only to my own earphone, so that I hear the interpretation intended for me.
7. As the active speaker, I want my own translated audio excluded from my earphone, so that I am not distracted by an echo of my own speech.
8. As either participant, I want source and translated subtitles to identify the actual accepted direction, so that I can visually verify who said what and what was translated.
9. As either participant, I want source-language detection constrained to the configured A/B languages, so that unrelated ambient speech cannot be misrouted to an earphone.
10. As either participant, I want malformed or non-candidate provider language results rejected, so that invalid protocol output is never routed.
11. As either participant, I understand that Azure may map speech outside A/B to A or B, so that I do not mistake candidate-constrained LID for reliable third-language rejection.
12. As either participant, I understand that overlapping-speech detection is unsupported in this version, so that I do not rely on a silent-rejection guarantee.
13. As either participant, I want silent or too-short capture fragments discarded, so that blank subtitles, empty playback, and empty history entries are avoided.
14. As either participant, I want natural alternation between A and B speakers, so that a completed or draining prior Turn never blocks the next accepted speaker.
15. As either participant, I want target-earphone playback to remain ordered per listener, so that translated sentences do not overlap or arrive out of sequence.
16. As either participant, I want a temporary provider failure to stop only the affected automatic Turn and show a recoverable error, so that I can continue the conversation after recovery.
17. As either participant, I want to pause automatic mode, so that no microphone audio is captured or routed while we need privacy or a break.
18. As either participant, I want to resume automatic mode, so that the next accepted utterance again starts with automatic language detection.
19. As either participant, I want to stop automatic mode safely, so that active capture ends and pending translation audio follows the existing safe shutdown rules.
20. As either participant, I want already completed Turns to remain visible after a pause, stop, error, or force recovery, so that successful translation results are not lost.
21. As an authenticated user, I want only completed source/translation pairs from automatic mode persisted as 翻译会话 history, so that my 本机历史副本 and 云端历史副本 never contain partial, rejected, or ambiguous speech.
22. As an authenticated user, I want the existing history synchronization and encryption behavior to apply unchanged to automatic-mode completed Turns, so that automatic mode does not weaken account or privacy guarantees.
23. As a user who changes the selected languages, I want automatic mode to require an idle session before applying the change, so that an active Turn cannot change direction or earphone route midway.
24. As a Debug user, I want automatic mode to work with the selected translation provider for new automatic Turns, so that I can evaluate Azure or the default Volcengine path without modifying an active Turn.
25. As a Release user, I want automatic mode to retain the normal product-facing experience without exposing Debug-only provider or voice controls, so that release UI remains stable.
26. As a user receiving a microphone-permission denial, I want automatic mode not to start capture and to explain the required permission, so that no silent failure is mistaken for translation.
27. As a user performing 强制恢复翻译, I want the current incomplete automatic Turn cancelled while completed Turns remain, so that I can immediately continue a safe conversation.
28. As a user rotating the device or leaving the face-to-face screen, I want automatic mode to stop or transition through the existing safe lifecycle, so that capture and playback are never orphaned.

## Implementation Decisions

- Automatic mode is a face-to-face-only mode. It uses the two explicitly selected session languages as the complete language-identification candidate set. It does not identify arbitrary languages and does not choose a language pair for the user.
- Each accepted speech segment is classified before its translation direction is committed. Classification A maps to source=A/target=B; classification B maps to source=B/target=A. The detected source language, not a manually selected side, determines the direction.
- The existing face-to-face coordination boundary remains the sole authority for automatic Turn lifecycle, capture state, pending sessions, subtitle aggregation, playback drainage, cancellation, pause/resume, stopping, force recovery, and completed-Turn retention. Automatic LID contributes a classification decision; it must not create a competing state model.
- The two physical participants are represented by their configured language ownership for this session. A→B output routes exclusively to B's earphone and B→A output routes exclusively to A's earphone. The existing opposite-side playback routing rule applies once the detected speaker/target side is resolved.
- A source speaker never receives that Turn's target-language PCM. No speaker-side local monitoring or duplicated TTS route is introduced by automatic mode.
- Azure Unified Speech v2 LID is constrained to the configured two-language candidate set and emits its finalized locale before source/translation finals and TTS. The direct protocol does not expose a usable confidence value here, so this implementation does not claim a low-confidence filter. Empty, unsupported, and malformed detected outcomes are rejected before translation output.
- Azure may classify third-language speech as one of the two candidates. This is an accepted Scheme 1 limitation, not silent rejection; the product does not claim arbitrary-language detection or reliable third-language exclusion.
- Overlapping-speech detection is not implemented. The product does not attempt speaker separation, mixed-language transcription, or claim that overlap is discarded; this is an explicit unsupported limitation.
- The provider boundary must carry automatic-mode intent and the two candidate languages, and must return the detected source language with subtitles/final outcomes. Provider-specific LID support is hidden behind this protocol; the Android UI must not infer language from subtitle text.
- Automatic mode may start a fresh Azure translation session for each accepted utterance/segment. It sends exactly two candidate Azure locales in `speech.context.languageId` with `DetectAtAudioStart`, and both Azure translation codes as targets so either detected direction is available. The final detected language selects the opposite target locale and voice before TTS; no active session is reconfigured. Volcengine does not expose automatic mode and candidate requests to it are rejected by the server.
- Translation/TTS results use the existing ordered playback-work mechanism. Each accepted Turn has one resolved target route; queued PCM cannot change destination after route assignment.
- Completed automatic Turns use the existing definition of 已完成 Turn: both source and translation are final and safe to retain. Rejected, partial, failed, or ambiguous fragments are not added to a 翻译会话 or either history copy.
- No Room schema, Cloud API history schema, account model, OTA behavior, or release package identity is changed.
- Default translation provider remains Volcengine. Debug-only Azure/provider/voice controls retain their existing visibility rules. Azure continuous simultaneous interpretation improvements are out of scope; automatic mode uses finalized accepted segments for safe routing and playback.

## Testing Decisions

- The primary behavior seam is the existing face-to-face coordinator. Tests describe externally observable state transitions and emitted intents: detected source language selects the opposite target route, only the target route receives playback work, accepted Turns complete, and rejected inputs create neither playback nor completed history.
- Coordinator tests cover A→B and B→A detection directions, finalized language-before-subtitle/TTS ordering, alternating speakers while prior TTS drains, pause/resume/stop, force recovery, provider-failure cleanup, empty final pairs, and malformed/non-candidate language. They do not claim low-confidence filtering or overlap detection.
- Protocol tests cover automatic start metadata with exactly two candidate languages and detected-language event validation. They reject missing, duplicated, unsupported, or out-of-candidate language values before UI routing.
- Agent/provider tests verify Azure Unified Speech v2 endpoint/configuration, exactly two locale candidates, both Azure translation targets, finalized LID parsing, and `detected_language` before subtitle-final/TTS. Server tests reject malformed candidates and all Volcengine automatic requests. Real Azure smoke tests validate supported production paths after deterministic tests pass.
- Android UI/instrumentation tests verify automatic-mode entry, accessible status messaging, displayed detected direction, pause/resume/stop controls, and that emitted playback route targets only the other participant. Existing face-to-face screen and coordinator tests are the prior art.
- History tests verify that only completed automatic Turns are saved and synchronized; rejected or interrupted fragments are absent from 本机历史副本 and cloud-sync outbox.
- A good test observes user-facing decisions—accepted direction, subtitles, earphone target, lifecycle controls, and retained completed Turn—rather than private timing, coroutine, queue, or provider implementation details.

## Out of Scope

- Simultaneous translation of two overlapping speakers, diarization, speaker biometrics, or best-effort mixed-audio separation.
- Automatic language-pair selection beyond the two languages the user explicitly configured.
- Reliable rejection of a third language, arbitrary-language LID, or live candidate-language editing during an active automatic Turn. Azure may map a third language to one configured candidate.
- Azure hypothesis-by-hypothesis TTS playback, predictive playback, or changes to the established final-result TTS policy.
- Changes to manual face-to-face mode, single-person 同传, account authentication, entitlement policy, Room schema, Cloud API history schema, OTA security, or release package/signing configuration.
- Making Debug-only engine, voice, or diagnosis controls visible in release builds.

## Further Notes

- This feature follows a safety-over-recall rule for malformed/unsupported provider output, empty final pairs, and provider failures: no target TTS or history is safer than partial routing. Candidate-constrained Azure LID can map a third language to A/B, and overlap detection is unsupported; both limitations require real-device validation.
- Product validation should prioritize Chinese↔Vietnamese and English↔Vietnamese on real dual-ear hardware, including alternation, short pauses, ambient speech, simultaneous speech, pause/resume, force recovery, and history synchronization.
- The existing Azure provider has been validated for final source/translation/TTS session output. Automatic mode still requires an explicit LID-capable provider contract and production smoke validation before it is exposed as complete.
