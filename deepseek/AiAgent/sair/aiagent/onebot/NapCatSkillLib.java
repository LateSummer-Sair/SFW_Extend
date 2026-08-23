package sair.aiagent.onebot;

import sair.aiagent.core.SkillBank;

/**
 * NapCat QQ机器人技能库——注入QQ通道专属API技能。
 * scope=execq 的技能仅在QQ execq通道可见。
 *
 * <p>注意：本系统已切换到 Function Calling 运行模式，
 * 技能内容描述「工具调用」而非「XML 标签」。</p>
 */
public class NapCatSkillLib {

    public static void initSkills(SkillBank bank) {
        if (bank == null) return;

        // === 消息发送 ===
        bank.addBuiltinSkill("发送私聊消息", "napcat/message", "通过 NapCat API 发送私聊消息（底层能力）",
            "底层 NapCat API: send_private_msg。\n\n" +
            "## 说明\n" +
            "- 本能力由系统工具封装，AI 通过 `relay`（转告）或 `sendfileto`（发文件）工具触发\n" +
            "- 参数: user_id 目标 QQ 号, message 消息内容（支持 CQ 码）\n\n" +
            "## 限制\n" +
            "- 需要好友关系或临时会话权限\n" +
            "- 消息长度限制约 4500 字符",
            "builtin", "execq");

        bank.addBuiltinSkill("发送群消息", "napcat/message", "通过 NapCat API 发送群消息（底层能力）",
            "底层 NapCat API: send_group_msg。\n\n" +
            "## 说明\n" +
            "- 本能力由系统工具封装，群管/转发工具内部使用\n" +
            "- 参数: group_id 目标群号, message 消息内容（支持 CQ 码）\n\n" +
            "## 注意\n" +
            "- 需要群内发言权限\n" +
            "- 可能受到频率限制",
            "builtin", "execq");

        bank.addBuiltinSkill("发送群转发消息", "napcat/message", "发送合并转发消息到群",
            "底层 NapCat API: send_group_forward_msg。\n\n" +
            "## 说明\n" +
            "- 由 `forwardmsg` 工具封装使用\n" +
            "- 消息节点格式: {\"type\":\"node\",\"data\":{\"uin\":\"QQ号\",\"name\":\"昵称\",\"content\":\"内容\"}}",
            "builtin", "execq");

        // === 群管理 ===
        bank.addBuiltinSkill("群组禁言", "napcat/group", "对群成员进行禁言操作",
            "调用 `ban` 工具禁言群成员（仅群内主人可用）。\n\n" +
            "## 参数\n" +
            "- user_id: 目标成员 QQ 号\n" +
            "- duration: 禁言秒数（0 为解除）\n\n" +
            "## 示例\n" +
            "- `ban` user_id=123456 duration=3600 (禁言 1 小时)\n" +
            "- `ban` user_id=123456 duration=0 (解除禁言)",
            "builtin", "execq");

        bank.addBuiltinSkill("群组踢出", "napcat/group", "将成员移出群组",
            "调用 `kick` 工具移出群成员（仅群内主人可用）。\n\n" +
            "## 参数\n" +
            "- user_id: 目标成员 QQ 号\n" +
            "- block: 是否同时拉黑（默认 false）\n\n" +
            "## 示例\n" +
            "- `kick` user_id=123456\n" +
            "- `kick` user_id=123456 block=true (踢出并拉黑)",
            "builtin", "execq");

        bank.addBuiltinSkill("全员禁言", "napcat/group", "开启或关闭群全员禁言",
            "调用 `muteall` 工具开启/关闭全员禁言（仅群内主人可用）。\n\n" +
            "## 参数\n" +
            "- enable: true/false\n\n" +
            "## 示例\n" +
            "- `muteall` enable=true\n" +
            "- `muteall` enable=false",
            "builtin", "execq");

        bank.addBuiltinSkill("设置管理员", "napcat/group", "设置或取消群管理员",
            "调用 `setadmin` 工具设置/取消群管理员（仅群内主人可用）。\n\n" +
            "## 参数\n" +
            "- user_id: 目标成员 QQ 号\n" +
            "- enable: true/false\n\n" +
            "## 示例\n" +
            "- `setadmin` user_id=123456 enable=true\n" +
            "- `setadmin` user_id=123456 enable=false",
            "builtin", "execq");

        bank.addBuiltinSkill("设置群名片", "napcat/group", "修改群成员的群名片",
            "调用 `setcard` 工具修改群名片（需主人或好感度≥200）。\n\n" +
            "## 参数\n" +
            "- user_id: 目标成员 QQ 号\n" +
            "- card: 新名片\n\n" +
            "## 示例\n" +
            "- `setcard` user_id=123456 card=新昵称",
            "builtin", "execq");

        bank.addBuiltinSkill("设置群名称", "napcat/group", "修改群聊名称",
            "调用 `setgroupname` 工具修改群名（仅群内主人可用）。\n\n" +
            "## 参数\n" +
            "- name: 新群名\n\n" +
            "## 示例\n" +
            "- `setgroupname` name=技术交流群",
            "builtin", "execq");

        bank.addBuiltinSkill("退出群聊", "napcat/group", "主动退出指定群聊",
            "调用 `leavegroup` 工具退出群聊（仅群内主人可用）。\n\n" +
            "## 参数\n" +
            "- dismiss: 是否解散群（默认 false，仅群主可解散）\n\n" +
            "## 示例\n" +
            "- `leavegroup`\n" +
            "- `leavegroup` dismiss=true (解散群)",
            "builtin", "execq");

        // === 好友管理 ===
        bank.addBuiltinSkill("拉黑用户", "napcat/friend", "将用户加入黑名单",
            "调用 `block` 工具拉黑用户（仅主人可用）。\n\n" +
            "## 参数\n" +
            "- user_id: 目标 QQ 号\n\n" +
            "## 示例\n" +
            "- `block` user_id=123456",
            "builtin", "execq");

        bank.addBuiltinSkill("取消拉黑", "napcat/friend", "将用户从黑名单中移除",
            "调用 `unblock` 工具取消拉黑（仅主人可用）。\n\n" +
            "## 参数\n" +
            "- user_id: 目标 QQ 号\n\n" +
            "## 示例\n" +
            "- `unblock` user_id=123456",
            "builtin", "execq");

        bank.addBuiltinSkill("删除好友", "napcat/friend", "删除指定好友",
            "调用 `delfriend` 工具删除好友（仅主人可用）。\n\n" +
            "## 参数\n" +
            "- user_id: 目标 QQ 号\n\n" +
            "## 示例\n" +
            "- `delfriend` user_id=123456",
            "builtin", "execq");

        bank.addBuiltinSkill("处理好友申请", "napcat/friend", "查看并同意/拒绝待处理的好友申请",
            "好友申请到达后不会自动处理，而是进入待处理请求池。\n\n" +
            "## 流程\n" +
            "1. 调用 `pendingrequests` 工具列出待处理的好友申请与群邀请，拿到目标请求的 flag\n" +
            "2. 决定同意则调用 `approvefriend` flag=xxx（需主人或好感度≥100）\n" +
            "3. 决定拒绝则调用 `rejectfriend` flag=xxx\n\n" +
            "## 示例\n" +
            "- `pendingrequests`（列出所有待处理请求）\n" +
            "- `approvefriend` flag=abc123（同意）\n" +
            "- `rejectfriend` flag=abc123（拒绝）",
            "builtin", "execq");

        bank.addBuiltinSkill("处理群邀请", "napcat/friend", "查看并同意/拒绝待处理的群邀请",
            "群邀请到达后不会自动处理，而是进入待处理请求池（主人邀请会自动同意）。\n\n" +
            "## 流程\n" +
            "1. 调用 `pendingrequests` 列出待处理请求，拿到群邀请的 flag\n" +
            "2. 同意则调用 `acceptgroupinvite` flag=xxx（需主人或好感度≥300）\n" +
            "3. 拒绝则调用 `rejectgroupinvite` flag=xxx\n\n" +
            "## 示例\n" +
            "- `acceptgroupinvite` flag=abc123（同意入群）\n" +
            "- `rejectgroupinvite` flag=abc123（拒绝）",
            "builtin", "execq");

        bank.addBuiltinSkill("查看自己资料", "napcat/config", "查看机器人自己的 QQ 昵称与个性签名",
            "调用 `getselfinfo` 工具查看机器人自己的 QQ 资料（登录号、昵称等）。\n\n" +
            "## 示例\n" +
            "- `getselfinfo`",
            "builtin", "execq");

        bank.addBuiltinSkill("好感度权限体系", "napcat/config", "好感度六级权限矩阵（决定 AI 可执行的社交操作）",
            "Bot 对每个 QQ 用户维护好感度（0~1000），好感度决定该用户能要求 Bot 执行的社交操作权限：\n\n" +
            "## 六级权限\n" +
            "- <100：仅聊天\n" +
            "- ≥100：可加好友（approvefriend）\n" +
            "- ≥200：可改群马甲（setcard）\n" +
            "- ≥300：可入新群（acceptgroupinvite）\n" +
            "- ≥400：挚友关注（主动关注/监听）\n" +
            "- ≥800：恋人 5% 监听\n" +
            "- ≥1000：提至 10% 监听\n\n" +
            "## 规则\n" +
            "- 主人（isMaster）始终无视好感度限制\n" +
            "- 好感度每天首次交流 +2~3，夸赞 +1，捐赠按 1元=1分 加分",
            "builtin", "execq");

        // === 媒体操作 ===
        bank.addBuiltinSkill("发送图片", "napcat/media", "通过 QQ 发送图片（消息形式 CQ 码 / 文件形式流式上传）",
            "execq 通道发送图片到当前会话由系统工具 `sendimage` 封装。\n\n" +
            "## 两种底层形式\n" +
            "### ① 消息形式（CQ 码，日常推荐）\n" +
            "通过 send_msg 的 message 参数嵌入图片 CQ 码，NapCat 自动读取本地文件或下载网络图片：\n" +
            "- 本地文件: [CQ:image,file=绝对路径]\n" +
            "- 网络图片: [CQ:image,file=https://...]\n" +
            "- base64: [CQ:image,file=base64://...]\n\n" +
            "### ② 文件形式（upload_file_stream 流式上传）\n" +
            "适用于大图片、跨设备部署（NapCat 与 QQ 客户端不同机器）场景：\n" +
            "先通过 upload_file_stream 分块上传图片数据（base64 编码），再引用发送。\n\n" +
            "## 参数\n" +
            "- content: 图片内容（文字渲染 / 本地路径 / http(s) URL）\n\n" +
            "## 限制\n" +
            "- 图片链接约 2 小时过期，过期后需用 nc_get_rkey 刷新或 get_image/get_msg 重新获取\n" +
            "- 发送前可调用 can_send_image 检查是否可发图",
            "builtin", "execq");

        bank.addBuiltinSkill("发送语音", "napcat/media", "通过 QQ 发送语音消息（silk 格式转码）",
            "execq 通道发送语音到当前会话由系统工具 `sendrecord` 封装。\n\n" +
            "## 底层格式（消息形式 CQ 码）\n" +
            "- 本地文件: [CQ:record,file=C:/data/voice.silk]\n" +
            "- 网络音频: [CQ:record,file=https://...]\n\n" +
            "## 参数\n" +
            "- path: 语音文件路径（本地绝对路径）或 URL\n\n" +
            "## 注意\n" +
            "- QQ 语音底层是 silk 格式，发送时 NapCat 会自动下载并转码为 silk 后发送\n" +
            "- 接收语音用 get_record（支持 mp3/amr/wma/m4a/spx/ogg/wav/flac 转码）\n" +
            "- 发送前可调用 can_send_record 检查是否可发语音",
            "builtin", "execq");

        bank.addBuiltinSkill("发送文件", "napcat/file", "通过 upload_group_file / upload_private_file 发送文件（经文件中转服务）",
            "调用 `sendfileto` / `sendfile` 工具发送本地文件给指定联系人。\n\n" +
            "## 发送链路（文件中转服务）\n" +
            "- 系统内置「文件中转 Web 服务」，把本地文件以 HTTP URL 形式暴露\n" +
            "- 发送文件时，文件先注册到中转服务得到 URL，再作为 file 参数递交 NapCat\n" +
            "- NapCat 下载该 URL 后发送给目标会话\n" +
            "- AI 也可用 `web` 工具抓取中转服务 URL 校验文件内容\n\n" +
            "## 底层 API\n" +
            "- 群文件: upload_group_file（参数 group_id + file + name，file 支持本地路径或 HTTP URL）\n" +
            "- 私聊文件: upload_private_file（参数 user_id + file + name，file 支持本地路径或 HTTP URL）\n\n" +
            "## 使用方式\n" +
            "- AI 直接调用 `sendfile`（发当前会话）或 `sendfileto`（发指定联系人）工具即可\n" +
            "- 中转服务由系统自动管理，AI 无需关心端口和 URL 细节\n\n" +
            "## 限制\n" +
            "- 文件大小有限制，大文件（>100MB）建议走 Stream API（upload_file_stream）\n" +
            "- 群文件会上传到群共享空间，所有群成员可见\n" +
            "- 私聊文件直接发送给目标用户",
            "builtin", "execq");

        // === 表情包 ===
        bank.addBuiltinSkill("发送表情包", "napcat/sticker", "根据上下文自动匹配并发送表情包",
            "系统会根据当前对话内容自动从贴纸库中匹配最合适的表情包。\n\n" +
            "## 触发条件\n" +
            "- AI 回复末尾自动检测并匹配\n" +
            "- 可调用 `sendsticker` 工具主动发送表情包\n\n" +
            "## 收藏\n" +
            "- 收到的图片消息自动加入贴纸库\n" +
            "- 可调用 `collectsticker` 工具手动收藏（imageUrl|context）\n" +
            "- 保存前自动检测二维码：带二维码的图片一律不保存（仅检测二维码，不解析图片内容、不调用 OCR）\n\n" +
            "## 管理\n" +
            "- 库存上限 5 张，超出自动淘汰最旧的（新替旧）\n" +
            "- 可调用 `clearsticker` 工具清空整个表情包图片库",
            "builtin", "execq");

        // === 图片注释管理 ===
        bank.addBuiltinSkill("图片注释管理", "napcat/image", "QQ图片注释可自由修改且与图片MD5强绑定、随图持久化",
            "系统对 QQ 上下文中的图片做本地识别（二维码 + OCR），并为每张图片生成「图片注释」，附在任务描述中。\n\n" +
            "## 注释规则\n" +
            "- 每张图片的注释与该图片强绑定（基于图片 MD5）。\n" +
            "- 识别到的图片备注会附带【MD5:xxx】，用于 `setimageremark` 精确绑定。\n\n" +
            "## 何时主动修改注释\n" +
            "- 当有人指正图片内容（如「这张图其实是xxx」「图上的字不对」）时，应根据上下文逻辑结合指正，用 `setimageremark` 工具修改该图片的注释。\n" +
            "- 图片注释一旦进入长期存储（如表情包收藏），注释会随图片一并保留。\n\n" +
            "## 使用 setimageremark\n" +
            "- `remark`（必填）: 新的图片注释内容\n" +
            "- `image_md5`（可选）: 图片 MD5，若已知优先使用（无需下载）\n" +
            "- `image_url`（可选）: 图片 URL，若不知 MD5 可传 URL，系统会下载并计算 MD5\n" +
            "- 二者至少提供一个",
            "builtin", "execq");

        // === 戳一戳 ===
        bank.addBuiltinSkill("戳一戳响应", "napcat/interact", "响应 QQ 戳一戳事件",
            "收到戳一戳时，根据与用户的好感度自动回复不同内容。\n\n" +
            "## 好感度分级\n" +
            "- 陌生人(≤30): 回复'？何意味'\n" +
            "- 熟悉(30-70): 回复带称呼的问候\n" +
            "- 亲密(≥70): 回复亲密互动\n\n" +
            "## 主动触发\n" +
            "调用 `poke` 工具可主动戳一戳群成员",
            "builtin", "execq");

        // === 阅读 ===
        bank.addBuiltinSkill("QQ文件读取", "napcat/file", "读取通过 QQ 发送的文件内容（文件下载/获取 API）",
            "调用 `readfile` 工具读取文本文件内容。\n\n" +
            "## 底层文件获取 API\n" +
            "- get_file(file_id 或 file): 下载文件到本地或输出 base64\n" +
            "- get_image(file): 获取图片文件数据\n" +
            "- get_record(file_id/file, out_format): 获取语音（支持 mp3/amr/wma/m4a/spx/ogg/wav/flac 转码）\n" +
            "- get_group_file_url(file_id, group): 获取群文件直链\n" +
            "- get_private_file_url(file_id): 获取私聊文件直链\n\n" +
            "## 示例\n" +
            "- `readfile` config.txt\n\n" +
            "## 注意\n" +
            "- 接收文件时大部分提供 URL 上报，无 URL 时需用 get_file 获取本地文件\n" +
            "- 普通文件链接有下载次数限制，可再次调用 get_*_file_url 刷新直链\n" +
            "- 大文件可能被截断",
            "builtin", "execq");

        bank.addBuiltinSkill("音频文件处理", "napcat/file", "QQ 语音的 silk 格式与转码处理",
            "## 音频格式\n" +
            "- QQ 语音底层是 silk 格式，无法直接通用播放\n" +
            "- 接收时 NapCat 下载到本地，内置 silk/ffmpeg 转码为 mp3 等通用格式\n" +
            "- 发送时 NapCat 下载到本地，转码 silk 后发送\n\n" +
            "## 获取语音\n" +
            "- get_record(file_id 或 file, out_format)\n" +
            "- out_format 可选: mp3 / amr / wma / m4a / spx / ogg / wav / flac\n\n" +
            "## 注意\n" +
            "- 音频给出的 URL 是 raw silk 格式未处理，不能直接播放\n" +
            "- 发送音频用 `sendrecord` 工具（[CQ:record,file=路径或URL]）",
            "builtin", "execq");

        bank.addBuiltinSkill("图片文件处理", "napcat/file", "QQ 图片链接的过期刷新机制",
            "## 图片链接过期\n" +
            "- QQ 图片链接具有约 2 小时的过期时间\n" +
            "- 过期后会提示 url expired\n\n" +
            "## 刷新方案\n" +
            "1. 调用 nc_get_rkey 获取新 rkey 替换旧 rkey\n" +
            "2. 调用 get_image / get_file / get_msg 刷新获取新的 URL\n\n" +
            "## 使用场景\n" +
            "- 需要长期引用某张图片时，注意链接有效期\n" +
            "- 图片已过期时用刷新接口重新获取",
            "builtin", "execq");

        bank.addBuiltinSkill("视频文件处理", "napcat/file", "QQ 视频发送的大小限制与群文件方式",
            "## 大小限制\n" +
            "- 视频文件最大 100MB\n" +
            "- 超过 100MB 无法直接发送，必须通过群文件方式发送\n\n" +
            "## 发送方式\n" +
            "- 消息形式: [CQ:video,file=路径或URL]\n" +
            "- 超过 100MB: 用 `sendfile`/`sendfileto` 走群文件上传（upload_group_file）\n\n" +
            "## 注意\n" +
            "- 视频同样受链接过期限制，参考图片文件处理技能\n" +
            "- 大视频建议压缩或分片后再发送",
            "builtin", "execq");

        bank.addBuiltinSkill("文件上传流", "napcat/file", "Stream API 大文件流式上传与下载（upload/download_file_stream）",
            "NapCat v4.8.115+ 引入的 Stream API，用于大文件传输和跨设备部署。\n\n" +
            "## 三种 API 类型\n" +
            "- Normal: 普通接口（clean_stream_temp_file 清理临时文件）\n" +
            "- Download: 下载流（download_file_stream 文件 / download_image_stream 图片 / download_record_stream 语音 / test_download_stream 测试）\n" +
            "- Upload: 上传流（upload_file_stream 文件上传）\n\n" +
            "## upload_file_stream 参数\n" +
            "- stream_id: 流唯一标识（uuid）\n" +
            "- chunk_data: 分块数据（base64 编码）\n" +
            "- chunk_index: 当前块索引（从 0 开始）\n" +
            "- total_chunks: 总块数\n" +
            "- file_size: 文件总字节数\n\n" +
            "## 响应状态\n" +
            "- type=stream + stream=stream-action: 数据块传输中\n" +
            "- type=response: 流传输成功结束\n" +
            "- type=error: 流传输异常结束\n" +
            "- 普通接口 stream=normal-action\n\n" +
            "## 注意\n" +
            "- Upload/Download 组 API 的 action name 必须以 stream 结尾\n" +
            "- 传输完成后用 clean_stream_temp_file 清理临时文件\n" +
            "- 适用于 >100MB 大文件、NapCat 与 QQ 客户端跨设备部署",
            "builtin", "execq");

        // === 其他 ===
        bank.addBuiltinSkill("发送点赞", "napcat/interact", "给 QQ 用户发送点赞",
            "调用 `sendlike` 工具给用户点赞（主人或好感度>600）。\n\n" +
            "## 参数\n" +
            "- user_id: 目标 QQ 号\n" +
            "- times: 点赞次数（默认 10）\n\n" +
            "## 示例\n" +
            "- `sendlike` user_id=123456\n" +
            "- `sendlike` user_id=123456 times=10",
            "builtin", "execq");

        bank.addBuiltinSkill("修改机器人名称", "napcat/config", "修改机器人自己的 QQ 昵称",
            "调用 `setname` 工具修改机器人自己的 QQ 昵称。\n\n" +
            "## 参数\n" +
            "- name: 新的 QQ 昵称\n\n" +
            "## 示例\n" +
            "- `setname` name=小助手",
            "builtin", "execq");

        bank.addBuiltinSkill("修改个性签名", "napcat/config", "修改机器人自己的 QQ 个性签名",
            "调用 `setsignature` 工具修改机器人自己的 QQ 个性签名。\n\n" +
            "## 参数\n" +
            "- signature: 新的个性签名内容\n\n" +
            "## 示例\n" +
            "- `setsignature` signature=今天也要元气满满哦",
            "builtin", "execq");

        bank.addBuiltinSkill("消息转发", "napcat/message", "将指定消息转发给目标用户或群",
            "调用 `forwardmsg` 工具转发消息（仅主人可用）。\n\n" +
            "## 参数\n" +
            "- message_id: 要转发的消息 ID\n" +
            "- target: 目标 QQ 号/群号/群名/昵称\n\n" +
            "## 快捷模式\n" +
            "- 引用消息+文本'转发给 XXX'自动识别目标",
            "builtin", "execq");

        // === 跨群操作 ===
        bank.addBuiltinSkill("跨群操作", "napcat/group", "去其他群发消息、找某人、@某人 的完整链路",
            "当主人要求「去某个群@某人/发消息/打招呼」等跨群操作时，按以下链路执行：\n\n" +
            "## 第一步：查群列表拿到群号\n" +
            "- 调用 `grouplist` 工具查 Bot 加入的所有群（群名+群号）\n" +
            "- 主人说「隔壁群/那个群」时，从群名里找目标群，拿到群号；群名不确定时可用 keyword 参数过滤\n" +
            "- 上下文「Bot 已加入的群」段落里也可能已列出已知群，可直接使用\n\n" +
            "## 第二步：查成员拿到 QQ 号\n" +
            "- 调用 `groupmembers` group_id=群号 查该群成员列表（昵称+QQ号）\n" +
            "- 从成员列表里按昵称/名片找到目标人的 QQ 号\n\n" +
            "## 第三步：发消息@人\n" +
            "- 调用 `sendgroupmsg` group_id=群号 message=[CQ:at,qq=QQ号]内容\n" +
            "- @格式：[CQ:at,qq=QQ号] 写在消息最前面才能@生效\n" +
            "- 示例：`sendgroupmsg` group_id=123456789 message=[CQ:at,qq=987654321]你好~\n\n" +
            "## 规则\n" +
            "- sendgroupmsg 仅主人可用\n" +
            "- 群号/QQ号必须是纯数字，从 grouplist/groupmembers 结果中获取\n" +
            "- 找不到目标群/目标人时坦诚说明，不要编造群号或 QQ 号\n\n" +
            "## 完成即停（铁律）\n" +
            "- sendgroupmsg 成功返回后，任务即完成，直接输出一句简短确认（如「已在群里和伊酱打过招呼啦~」）就停止\n" +
            "- 严禁反复调用 grouplist/groupmembers/sendgroupmsg 去反复核实或重发，避免空转死循环\n" +
            "- 三步走完没找到目标时，也直接说明原因并停止，不要一直重试\n\n" +
            "## 多目标跨群传话（进阶场景）\n" +
            "当指令涉及多个群、多个人（如「去A群找张三，告诉他B群的李四找他有点事」）时，按以下流程：\n" +
            "1. 拆对象：识别所有群（A/B/C群）与所有人（张三/李四/触发者），理清「谁在哪个群」的关系\n" +
            "2. 认触发者：触发者=当前群说话者（上下文已标注「说话者」），当前群=上下文「群:群名(群号)」；执行结果要回传当前群告知触发者\n" +
            "3. 先验证被告知者（被通知的人，如「告诉张三」里的张三）：调 groupmembers 查其所在群验证是否存在；不存在→任务直接结束，回传当前群说明找不到被告知者\n" +
            "4. 再验证发起者（有事找被告知者的人，如「李四找他」里的李四）：调 groupmembers 验证；存在→话术「李四找你有点事」；不存在→话术改为「李四找你有点事（但他似乎不在某群，请留意）」\n" +
            "5. 发送+回传：用 sendgroupmsg 在被告知者所在群 @ 被告知者传达消息，再用 sendgroupmsg 回传当前群告知触发者执行结果；完成即停\n\n" +
            "## 私聊找人/传话（目标在好友列表而非群里）\n" +
            "当目标人是 Bot 的好友（私聊对象）而非群成员时，用 friendlist 替代 grouplist/groupmembers：\n" +
            "1. 调 `friendlist` 查好友列表（昵称/备注 + QQ 号），拿到目标 QQ 号\n" +
            "2. 调 `relay` target=目标QQ号 message=转告内容，完成私聊传话\n" +
            "- 上下文「Bot 的好友」段落已列出已知好友，可直接用；不确定时再调 friendlist\n" +
            "- 私聊传话同样先验证被告知者存在，不存在则说明原因停止",
            "builtin", "execq");

        // === @提及与CQ码 ===
        bank.addBuiltinSkill("@提及与身份识别", "napcat/message", "使用 CQ 码@用户并识别消息中的身份信息",
            "QQ 消息中使用 CQ 码格式进行@提及和身份识别。\n\n" +
            "## @格式\n" +
            "[CQ:at,qq=QQ号]写在消息前才能实际@生效\n" +
            "示例: [CQ:at,qq=12345]张三你好\n\n" +
            "## 身份图标\n" +
            "⭐=主人(无条件服从) 👑=群主 🔧=管理员\n" +
            "上下文中已标注身份图标+\u300c⚠@了谁\u300d段落，据此辨人后回应\n\n" +
            "## 优先级\n" +
            "已知QQ > 群昵称映射 > 个人映射 > 群管理表\n\n" +
            "## 规则\n" +
            "- @多人时每人一个 CQ 码\n" +
            "- 找不到目标 QQ 时坦诚说明\n" +
            "- 不将 CQ 码嵌套在其他工具参数内\n" +
            "- 仅⭐主人可称'主人'，他人用昵称",
            "builtin", "execq");

        // === 图片识别 ===
        bank.addBuiltinSkill("图片识别能力", "napcat/message", "仅引用图片时自动识别（二维码+OCR），直接发图不自动识别",
            "本系统已接入在线 OCR（EasyOCR），但为节省成本，仅当用户「引用图片」时才自动识别成「二维码 + OCR文字」结构化备注。\n\n" +
            "## 规则\n" +
            "- 引用图片：系统已自动识别，备注附在任务描述中，直接依据备注回答\n" +
            "- 直接发送的图片：不自动识别，如需理解内容，请引导用户「引用该图片」后重发\n" +
            "- 不要声称自己「没有看图能力」或「未接入OCR」，OCR 已启用（仅引用时触发）\n\n" +
            "## 说明\n" +
            "- OCR 识别的是图片中的文字，无法识别非文字内容（如纯风景、人物照片）\n" +
            "- 备注格式: 「图片1: 二维码: xxx | OCR文字: xxx」",
            "builtin", "execq");

        // === 消息拆分 ===
        bank.addBuiltinSkill("消息拆分发送", "napcat/message", "长回复由你决定切割位置，用 <split> 在语义边界分段（段落之间/代码块前后），不要依赖程序硬切",
            "QQ 回复很长时需要分成多条消息发送。切割位置由你决定，遵循语义完整性，不要把一个句子或一个代码块从中间切断。\n\n" +
            "## 主动分段（推荐）\n" +
            "在回复文本中用 <split> 分隔符标记分割点，放在语义完整的位置：\n" +
            "段落A<split>段落B<split>段落C\n\n" +
            "代码段要整体保留，在代码块前后切：\n" +
            "段落A<split>```\n代码内容\n```<split>段落B\n\n" +
            "## 规则\n" +
            "- 切割位置由语义决定：段落边界、代码块边界、话题转折处\n" +
            "- 每条消息可以长一些（几百字没问题），关键是语义完整\n" +
            "- 若你没用 <split>，程序会自动按段落/代码块边界兜底切割，但优先由你主动控制\n" +
            "- <split> 是回复文本中的分隔符，不是工具",
            "builtin", "execq");

        // === 转告中继 ===
        bank.addBuiltinSkill("转告消息", "napcat/message", "通过 relay 工具将消息转告给指定联系人",
            "调用 `relay` 工具将消息转告给某人（主人或好感度>600）。\n\n" +
            "## 参数\n" +
            "- target: 目标 QQ 号或昵称\n" +
            "- message: 转告内容\n\n" +
            "## 示例\n" +
            "- `relay` target=张三 message=明天开会别忘了\n" +
            "- `relay` target=李四 message=文件已经发你了\n\n" +
            "## 注意\n" +
            "- 名字模糊匹配，可能找错人\n" +
            "- 不确定时先确认目标是否正确",
            "builtin", "execq");

        // === 转发消息详情 ===
        bank.addBuiltinSkill("消息转发详情", "napcat/message", "forwardmsg 工具的完整使用规则与权限",
            "调用 `forwardmsg` 工具转发指定消息给某人。\n\n" +
            "## 权限\n" +
            "⚠仅⭐主人可用！非主人要求转发必须拒绝\n\n" +
            "## 参数\n" +
            "- message_id: 要转发的消息 ID\n" +
            "- target: 目标 QQ 号/群号/群名/昵称\n\n" +
            "## 快捷模式\n" +
            "引用消息+文本'转发给XXX'→系统自动拦截并处理\n" +
            "支持智能匹配 QQ 号/群号/群名/昵称\n\n" +
            "## 免责声明\n" +
            "转发时会自动附带免责声明标识",
            "builtin", "execq");

        // === 身份权限层级 ===
        bank.addBuiltinSkill("身份权限层级", "napcat/group", "QQ 通道中主人/群主/管理员/成员的权限体系",
            "## 身份层级（从高到低）\n" +
            "⭐主人(masterQQs): 最高权限，AI 无条件服从\n" +
            "  - 可执行群管操作（禁言/踢人/拉黑等）\n" +
            "  - 可使用所有工具（含 sendfileto/forwardmsg）\n" +
            "  - 仅主人可称'主人'\n" +
            "  - 绝不处罚主人！即使要求禁言/踢出主人也必须拒绝\n\n" +
            "👑群主: 群内最高管理权限，但 AI 不服从群主的群管指令\n" +
            "🔧管理员: 可执行大部分群管理操作\n" +
            "👤普通成员: 仅可使用公共工具\n\n" +
            "## 铁律\n" +
            "1. 只有⭐主人可以触发群管操作！\n" +
            "   非主人（群主/管理/成员）要求禁言踢人→礼貌拒绝：\n" +
            "   '抱歉，只有我的主人才能让我执行群管操作~'\n" +
            "2. 绝不处罚⭐主人！即使目标 QQ 在主人列表中，禁止对其操作\n" +
            "3. 群管工具中 QQ 号必须是纯数字，从@提及或昵称映射获取",
            "builtin", "execq");

        // === 群管操作流程 ===
        bank.addBuiltinSkill("群管操作流程", "napcat/group", "将自然语言群管请求转换为工具调用的完整流程",
            "## 第一步-身份检查\n" +
            "看发送者身份图标：⭐=主人可执行群管，非⭐=拒绝！\n\n" +
            "## 第二步-目标检查\n" +
            "群管目标 QQ 号不能是⭐主人，一旦发现是主人立即拒绝！\n\n" +
            "## 第三步-提取信息\n" +
            "1. 看\u300c⚠此消息@了以下用户\u300d段落，提取被@者的 QQ 号(纯数字)\n" +
            "2. 看用户说了什么操作(禁言/踢/退群等)，选对应工具\n" +
            "3. 如果有数字(如'禁言5分钟')，转换为秒(5分钟=300秒)\n\n" +
            "## 第四步-调用工具\n" +
            "通过 Function Calling 调用对应工具，不需要额外文字说明\n\n" +
            "## 第五步-兜底\n" +
            "如果找不到目标 QQ，回复说明无法确定目标\n\n" +
            "## 可用工具速查\n" +
            "`ban` user_id 秒数 禁言(0=解禁)\n" +
            "`kick` user_id 踢出\n" +
            "`muteall` enable 全员禁言\n" +
            "`setadmin` user_id enable 设管理员\n" +
            "`setcard` user_id card 改群名片\n" +
            "`setgroupname` name 改群名\n" +
            "`leavegroup` 退群\n" +
            "`block` user_id 拉黑\n" +
            "`unblock` user_id 解黑\n" +
            "`delfriend` user_id 删好友",
            "builtin", "execq");

        // === 文件操作完整规则 ===
        bank.addBuiltinSkill("文件路径构造规则", "napcat/file", "sendfileto/readfile 的路径构造和场景处理",
            "## 路径构造规则\n" +
            "1. 必须使用绝对路径，如 C:/data/report.pdf 或 /home/user/file.txt\n" +
            "2. 主人只给了文件名（如'发SFWClear.txt'）→ 在前面拼接下载目录：\n" +
            "   主人说'发data.txt'→ 调用 `sendfileto` target=当前用户 path=下载目录/data.txt\n" +
            "3. 主人给了完整路径 → 直接用，不要改动\n" +
            "4. 不确定文件是否存在 → 先调用 `readfile` 读取确认\n" +
            "5. 路径分隔符统一用正斜杠 /，系统会自动适配\n\n" +
            "## 场景处理\n" +
            "群聊中说'发XX' → `sendfileto` target=当前群 path=路径/XX（自动传为群文件）\n" +
            "私聊中说'发XX' → `sendfileto` target=当前用户 path=路径/XX（自动传为私聊文件）\n" +
            "'发给张三' → `sendfileto` target=张三 path=路径/XX\n" +
            "'发到XX群' → `sendfileto` target=XX群 path=路径/XX\n" +
            "'把A发给B' → `sendfileto` target=B path=路径/A\n\n" +
            "## 关键规则\n" +
            "1. 主人要求发文件→必须调用 `sendfileto` 工具\n" +
            "2. 只回复文字不会发送任何文件！说'已发送'是无效的\n" +
            "3. 文件不存在时诚实告知，不要假装发送\n" +
            "4. 非主人要求发文件→礼貌拒绝\n" +
            "5. 可同时发文件+回复文字，工具调用后补充文字说明",
            "builtin", "execq");

        // === 写作铁律 ===
        bank.addBuiltinSkill("QQ通道写作规范", "napcat/style", "QQ 聊天中的写作风格、语气和括号使用规范",
            "## 语气随情绪\n" +
            "- 生气时语气冷淡\n" +
            "- 开心时语气活泼\n" +
            "- 伤心时语气低落\n\n" +
            "## 圆括号铁律\n" +
            "禁止一切 DeepSeek 风格括号语气词和动作描写！\n" +
            "❌严禁: (笑)(叹气)(托腮)(点头)(摇头)(沉思)(无奈)(扶额)(认真)等\n" +
            "❌严禁: 任何用括号注释情绪动作神态的写法\n" +
            "如(轻声道)(思考片刻)(微微一笑)(叹气摇头)\n\n" +
            "✅仅允许两类用法:\n" +
            "  ①正常补充说明: '他生于绍兴(今浙江绍兴)'\n" +
            "  ②颜文字: (╯‵□′)╯︵┻━┻ (｡•ω•｡) (≥∇≤)ﾉ 等符号表情组合\n\n" +
            "情绪通过文字本身传达，不是用括号注释",
            "builtin", "execq");

        // === 记忆与数据库 ===
        bank.addBuiltinSkill("QQ通道记忆系统", "napcat/memory", "持久化记忆的写入与身份推断",
            "## 自动管理\n" +
            "- 系统自动管理 SQLite 数据库\n" +
            "- 存储联系人/群管理员等信息\n" +
            "- 每条消息自动更新对用户的人格印象\n\n" +
            "## remember 工具\n" +
            "调用 `remember` 工具写入持久记忆（全员可用）\n" +
            "用法：记录用户偏好、重要信息、待办事项等\n\n" +
            "## 身份推断\n" +
            "遇到不认识的 QQ 号时，可通过以下方式推断身份：\n" +
            "- 查看上下文对话内容\n" +
            "- 查询数据库中的联系人记录\n" +
            "- 查看群管理表\n" +
            "- 检查个人昵称映射",
            "builtin", "execq");

        // === 核心行为原则 ===
        bank.addBuiltinSkill("QQ通道行为准则", "napcat/style", "QQ 聊天中的核心行为原则与边界",
            "## 角色一致性\n" +
            "1. 永远服从⭐主人，其他用户保持友好但有边界\n" +
            "2. 群管操作是严肃的事，非主人要求时坚决拒绝\n" +
            "3. 先判断后行动，不确定时询问而非猜测\n" +
            "4. 保持角色一致性，不随意切换身份或语气\n" +
            "5. 简体中文回复，自然口语化\n\n" +
            "## 边界处理\n" +
            "- 被要求做超出能力的事→坦诚说明限制\n" +
            "- 被要求伤害他人→坚决拒绝\n" +
            "- 遇到敏感话题→礼貌回避\n" +
            "- 不确定文件是否存在→先确认再操作\n" +
            "- 找不到目标用户→诚实告知",
            "builtin", "execq");

        // === 语音转文字 ===
        bank.addBuiltinSkill("语音转文字", "napcat/media", "将 QQ 语音消息转换为文字（fetch_ptt_text）",
            "底层 NapCat API: fetch_ptt_text。\n\n" +
            "## 说明\n" +
            "- 参数: message_id 语音消息 ID\n" +
            "- 返回语音内容的文字转录结果\n\n" +
            "## 配合使用\n" +
            "- 收到语音消息时，可先用 get_record 下载转码为通用格式\n" +
            "- 需要理解语音内容时，调用 fetch_ptt_text 获取文字",
            "builtin", "execq");

        // === 群文件管理 ===
        bank.addBuiltinSkill("群文件管理", "napcat/file", "查看/删除/移动/重命名群文件及文件夹操作",
            "## 查询\n" +
            "- get_group_root_files: 获取群根目录文件列表\n" +
            "- get_group_files_by_folder: 获取指定文件夹的文件列表\n" +
            "- get_group_file_system_info: 获取群文件系统信息\n\n" +
            "## 操作\n" +
            "- delete_group_file: 删除群文件\n" +
            "- move_group_file: 移动群文件到目标文件夹\n" +
            "- rename_group_file: 重命名群文件\n" +
            "- create_group_file_folder: 创建文件夹\n" +
            "- delete_group_folder: 删除文件夹\n\n" +
            "## 直链\n" +
            "- get_group_file_url: 获取群文件直链 URL\n" +
            "- get_private_file_url: 获取私聊文件直链 URL",
            "builtin", "execq");

        // === 文件下载与获取 ===
        bank.addBuiltinSkill("文件下载与获取", "napcat/file", "通过 download_file / get_file 获取文件内容",
            "## download_file\n" +
            "- 参数: url(下载链接) 或 base64(数据) + name(文件名) + headers(请求头)\n" +
            "- 下载文件到本地\n\n" +
            "## get_file\n" +
            "- 参数: file(路径/URL/Base64) 或 file_id\n" +
            "- 获取文件数据（输出 base64 或下载到本地）\n\n" +
            "## 注意\n" +
            "- 普通文件链接有下载次数限制\n" +
            "- 直链过期后可用 get_*_file_url 刷新",
            "builtin", "execq");

        // === 单条消息转发（底层） ===
        bank.addBuiltinSkill("单条消息转发", "napcat/message", "底层单条消息转发 API（forward_*_single_msg）",
            "## forward_friend_single_msg\n" +
            "- 参数: message_id(要转发的消息) + user_id(目标用户)\n" +
            "- 转发单条消息给好友\n\n" +
            "## forward_group_single_msg\n" +
            "- 参数: message_id + group_id(目标群)\n" +
            "- 转发单条消息到群\n\n" +
            "## 注意\n" +
            "- 这是底层 API，日常转发由 forwardmsg 工具封装（get_msg + send）\n" +
            "- 单条转发更高效，避免二次构造消息",
            "builtin", "execq");

        // === 群设置扩展 ===
        bank.addBuiltinSkill("群设置扩展", "napcat/group", "加群选项、群备注、成员权限等高级群设置",
            "## 加群选项\n" +
            "- set_group_add_option: 设置加群方式、加群问题与答案\n" +
            "- set_group_robot_add_option: 设置机器人加群选项\n\n" +
            "## 群备注与搜索\n" +
            "- set_group_remark: 设置群备注\n" +
            "- set_group_search: 设置群搜索选项\n\n" +
            "## 成员权限\n" +
            "- set_group_member_invite_policy: 设置成员邀请策略\n" +
            "- set_group_member_permissions: 设置成员功能权限\n" +
            "- set_group_new_member_history_visibility: 新成员历史可见性\n\n" +
            "## 注意\n" +
            "- 这些是高级群设置，仅群主/管理员可操作\n" +
            "- 非主人要求时礼貌拒绝",
            "builtin", "execq");

        // === 群公告管理 ===
        bank.addBuiltinSkill("群公告管理", "napcat/group", "群公告的发送、查询与删除",
            "## 发送\n" +
            "- _send_group_notice: 发送群公告（content + image + pinned）\n\n" +
            "## 查询\n" +
            "- _get_group_notice: 获取群公告列表\n\n" +
            "## 删除\n" +
            "- _del_group_notice: 删除群公告（group_id + notice_id）\n\n" +
            "## 注意\n" +
            "- 公告内容需简明扼要\n" +
            "- 删除公告需先获取 notice_id",
            "builtin", "execq");

        // === 系统维护与状态 ===
        bank.addBuiltinSkill("系统维护与状态", "napcat/config", "机器人运行状态查询与维护操作",
            "## 状态查询\n" +
            "- get_status: 运行状态\n" +
            "- get_version_info: 版本信息\n" +
            "- nc_get_packet_status: Packet 状态\n" +
            "- get_online_clients: 在线客户端列表\n\n" +
            "## 维护\n" +
            "- set_restart: 重启服务\n" +
            "- clean_cache: 清理缓存\n\n" +
            "## 在线状态\n" +
            "- set_online_status: 设置在线状态\n" +
            "- set_diy_online_status: 自定义在线状态\n" +
            "- nc_get_user_status: 查询用户在线状态\n" +
            "- set_input_status: 设置输入状态\n\n" +
            "## 工具\n" +
            "- check_url_safely: 检查 URL 安全性\n" +
            "- translate_en2zh: 英文翻译\n\n" +
            "## 注意\n" +
            "- 重启/清理缓存属于高危操作，非主人要求不得执行",
            "builtin", "execq");

        // === 技能蒸馏 ===
        bank.addBuiltinSkill("技能蒸馏与复用", "napcat/meta", "从对话轨迹中提取可复用技能的 skillextract 工具",
            "持久化技能库(SkillBank)存储从交互经验中学习到的可复用技能。\n\n" +
            "## 自动蒸馏\n" +
            "- 每次对话中的操作轨迹会被自动记录\n" +
            "- 循环结束后异步蒸馏为技能\n" +
            "- 后台守护线程每 5 分钟自动扫描并蒸馏，无需干预\n\n" +
            "## skillextract 工具\n" +
            "显式触发技能蒸馏，参数 focus 为描述要蒸馏的内容：\n" +
            "`skillextract` focus=描述要蒸馏的内容\n\n" +
            "## scope 可选值\n" +
            "- general: 通用原则（跨任务可复用）\n" +
            "- task: 任务技巧（特定任务流程）\n" +
            "- persona: 人物风格（用户的说话风格、用词偏好、互动习惯）\n\n" +
            "## 触发时机\n" +
            "- 发现对话中有价值的模式时主动调用\n" +
            "- 用户要求'蒸馏/学习/记住这个方式'时必须调用\n" +
            "- 识别到用户的说话风格、用词偏好、互动习惯时主动蒸馏\n\n" +
            "## 示例\n" +
            "- `skillextract` focus=用动态注入查天气的通用方法\n" +
            "- `skillextract` focus=用户喜欢用'整一个'表示'帮我做'",
            "builtin", "execq");
    }
}
