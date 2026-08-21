package com.weaver.seconddev.wecom.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * 会议信息 DTO，E10 会议模块触发同步时传入。
 *
 * <p>字段说明：时间统一为 <b>秒级 Unix 时间戳</b>，时区按 Asia/Shanghai 计算。</p>
 *
 * @author DuJiang
 */
@Slf4j
public class MeetingInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** E10 会议 ID（幂等与日历复用的唯一键） */
    private String meetingId;

    /** 会议标题 */
    private String title;

    /** 会议描述 / 议题 */
    private String description;

    /** 开始时间（秒级时间戳） */
    private Long startTime;

    /** 结束时间（秒级时间戳） */
    private Long endTime;

    /** 会议地点 / 会议室 */
    private String location;

    /** 参会人（E10 用户 ID），需映射为企业微信 userid；由映射层通过工具类（UserContext）获取手机号后换取 userid */
    private List<String> attendees = new ArrayList<>();

    public String getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(String meetingId) {
        this.meetingId = meetingId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public List<String> getAttendees() {
        return attendees;
    }

    public void setAttendees(List<String> attendees) {
        this.attendees = attendees == null ? new ArrayList<>() : attendees;
    }

    /**
     * 基本信息校验。
     *
     * @return 校验失败时的错误信息；校验通过返回 null
     * @author DuJiang
     */
    public String validate() {
        if (meetingId == null || meetingId.trim().isEmpty()) {
            return "meetingId 不能为空";
        }
        if (title == null || title.trim().isEmpty()) {
            return "title 不能为空";
        }
        if (startTime == null || endTime == null) {
            return "startTime/endTime 不能为空";
        }
        if (endTime <= startTime) {
            return "endTime 必须大于 startTime";
        }
        if (attendees == null || attendees.isEmpty()) {
            return "attendees 不能为空";
        }
        return null;
    }
}
