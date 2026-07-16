package com.potato.peacehaven.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * WechatApi Webhook 回调事件完整结构体
 * <p>
 * 官方报文格式（所有字段首字母大写）：
 * <pre>
 * {
 *   "TypeName": "AddMsg",
 *   "Appid": "wx_xxx",
 *   "Wxid": "wxid_xxx",
 *   "Data": { "MsgType": 1, "FromUserName": {"string":"..."}, ... }
 * }
 * </pre>
 * <p>
 * TypeName 取值：AddMsg / ModContacts / DelContacts / Offline / FinderSyncMsg / FinderBypMsg
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WechatApiCallbackEvent {

    /** 事件类型：AddMsg / ModContacts / DelContacts / Offline / FinderSyncMsg / FinderBypMsg */
    @JsonProperty("TypeName")
    private String typeName;

    /** 设备 appId（标识哪个虚拟设备） */
    @JsonProperty("Appid")
    private String appId;

    /** 当前登录微信号的 wxid */
    @JsonProperty("Wxid")
    private String wxid;

    /** 事件数据主体 */
    @JsonProperty("Data")
    private EventData data;

    // ========== 便捷方法 ==========

    /**
     * 获取消息发送方 wxid
     * <p>AddMsg 时从 FromUserName.string 取；ModContacts/DelContacts 时可能为 null
     */
    public String getFromWxid() {
        if (data == null) return null;
        if (data.getFromUserName() != null) return data.getFromUserName().getString();
        return null;
    }

    /**
     * 获取消息接收方 wxid
     */
    public String getToWxid() {
        if (data == null) return null;
        if (data.getToUserName() != null) return data.getToUserName().getString();
        return null;
    }

    /**
     * 获取消息正文（Content.string）
     */
    public String getContentString() {
        if (data == null || data.getContent() == null) return null;
        return data.getContent().getString();
    }

    /**
     * 获取 NewMsgId（用于去重）
     */
    public Long getNewMsgId() {
        return data != null ? data.getNewMsgId() : null;
    }

    /**
     * 获取 MsgType
     */
    public Integer getMsgType() {
        return data != null ? data.getMsgType() : null;
    }

    /**
     * 判断是否为群消息
     * <p>别人发的群消息：FromUserName.string 以 @chatroom 结尾
     * <p>自己发的群消息：ToUserName.string 以 @chatroom 结尾
     */
    public boolean isGroupMessage() {
        String from = getFromWxid();
        String to = getToWxid();
        return (from != null && from.endsWith("@chatroom"))
                || (to != null && to.endsWith("@chatroom"));
    }

    /**
     * 判断群消息中，机器人是否是发送者
     */
    public boolean isGroupSelfSent() {
        String to = getToWxid();
        return to != null && to.endsWith("@chatroom");
    }

    /**
     * 判断本消息是否由当前登录账号自己发出（非群消息场景）
     */
    public boolean isSelfSent() {
        return wxid != null && wxid.equals(getFromWxid());
    }

    /**
     * 获取群消息中的真实发送者 wxid
     * <p>群消息 Content.string 格式为 "wxid_xxxx:\n消息内容"
     * <p>若非群消息或解析失败，返回 null
     */
    public String getGroupSenderWxid() {
        if (!isGroupMessage() || isGroupSelfSent()) return null;
        String content = getContentString();
        if (content == null) return null;
        int sep = content.indexOf(":\n");
        if (sep > 0) {
            return content.substring(0, sep);
        }
        return null;
    }

    /**
     * 获取群聊 ID（@chatroom 结尾的那个）
     */
    public String getChatroomId() {
        String from = getFromWxid();
        if (from != null && from.endsWith("@chatroom")) return from;
        String to = getToWxid();
        if (to != null && to.endsWith("@chatroom")) return to;
        return null;
    }

    /**
     * 获取纯文本消息内容（群消息去掉 "wxid:\n" 前缀）
     */
    public String getPureContent() {
        String content = getContentString();
        if (content == null) return null;
        if (isGroupMessage() && !isGroupSelfSent()) {
            int sep = content.indexOf(":\n");
            if (sep > 0) {
                return content.substring(sep + 2);
            }
        }
        return content;
    }

    // ========== 内部类 ==========

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventData {

        @JsonProperty("MsgId")
        private Long msgId;

        @JsonProperty("NewMsgId")
        private Long newMsgId;

        /**
         * 消息类型：
         * 1=文本, 3=图片, 34=语音, 37=好友请求, 42=名片, 43=视频,
         * 47=Emoji, 48=位置, 49=复合消息, 10000=系统通知, 10002=XML系统
         */
        @JsonProperty("MsgType")
        private Integer msgType;

        @JsonProperty("FromUserName")
        private WxString fromUserName;

        @JsonProperty("ToUserName")
        private WxString toUserName;

        @JsonProperty("Content")
        private WxString content;

        @JsonProperty("Status")
        private Integer status;

        @JsonProperty("ImgStatus")
        private Integer imgStatus;

        @JsonProperty("ImgBuf")
        private ImgBuf imgBuf;

        @JsonProperty("CreateTime")
        private Long createTime;

        @JsonProperty("MsgSource")
        private WxString msgSource;

        @JsonProperty("PushContent")
        private WxString pushContent;

        @JsonProperty("MsgSeq")
        private Long msgSeq;

        // ===== ModContacts / DelContacts 专用字段 =====

        @JsonProperty("UserName")
        private WxString userName;

        @JsonProperty("NickName")
        private WxString nickName;

        @JsonProperty("BigHeadImgUrl")
        private String bigHeadImgUrl;

        @JsonProperty("SmallHeadImgUrl")
        private String smallHeadImgUrl;

        @JsonProperty("Sex")
        private Integer sex;

        @JsonProperty("Province")
        private String province;

        @JsonProperty("City")
        private String city;

        @JsonProperty("Signature")
        private String signature;

        /** ModContacts 群信息变更时包含此字段（群主 wxid） */
        @JsonProperty("ChatRoomOwner")
        private String chatRoomOwner;

        /** DelContacts 专用：删除场景 */
        @JsonProperty("DeleteContactScen")
        private Integer deleteContactScen;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WxString {
        @JsonProperty("string")
        private String string;

        /**
         * 安全获取值，null 时返回 null
         */
        public String getOrNull() {
            return string;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImgBuf {
        @JsonProperty("iLen")
        private Integer iLen;

        @JsonProperty("buffer")
        private String buffer;
    }
}
