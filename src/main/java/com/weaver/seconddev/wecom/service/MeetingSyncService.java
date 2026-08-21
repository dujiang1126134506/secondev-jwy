package com.weaver.seconddev.wecom.service;

import com.weaver.seconddev.wecom.client.WeComScheduleClient;
import com.weaver.seconddev.wecom.config.WeComConfig;
import com.weaver.seconddev.wecom.model.MeetingInfo;
import com.weaver.seconddev.wecom.model.WeComResult;
import com.weaver.seconddev.wecom.service.impl.DirectUserIdMapper;
import com.weaver.seconddev.wecom.service.impl.MobileUserIdMapper;
import com.weaver.seconddev.wecom.service.impl.PhoneUserIdMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会议 → 企业微信日程同步编排服务。
 *
 * <p>核心链路（对应实施文档总体架构）：</p>
 * <ol>
 *   <li>映射 E10 参会账号 → 企业微信 userid（映射缺失记录告警，不阻断整体同步）</li>
 *   <li>按 meetingId 复用共享日历（首次创建后缓存 cal_id，避免重复建日历）</li>
 *   <li>幂等：同一会议重复触发时，复用已创建的 schedule_id，改调 update</li>
 *   <li>创建 / 更新 / 取消日程，并按配置设置提前提醒</li>
 * </ol>
 *
 * <p>线程安全：日历 / 日程绑定关系存于内存 ConcurrentHashMap，可并发调用。</p>
 *
 * @author DuJiang
 */
@Slf4j
public class MeetingSyncService {

    /** 会议绑定关系：meetingId → (calId, scheduleId) */
    private static class MeetingBind {
        final String calId;
        volatile String scheduleId;

        MeetingBind(String calId) {
            this.calId = calId;
        }
    }

    private final WeComConfig config;
    private final WeComScheduleClient client;
    private final UserIdMapper userIdMapper;

    /** meetingId → 绑定关系（进程内缓存，等价于文档中 CAL_CACHE / schedule_id 缓存） */
    private final Map<String, MeetingBind> binds = new ConcurrentHashMap<>();

    /**
     * schedule_id 回写钩子：可在 E10 会议记录上回存企微 schedule_id，供改期/取消使用。
     * 默认为空实现；如需落库，请在此回调中按实际会议表结构执行 update SQL。
     */
    private ScheduleIdBackWriter scheduleIdBackWriter = (meetingId, scheduleId) -> { };

    /**
     * 回写回调接口。
     *
     * @author DuJiang
     */
    @FunctionalInterface
    public interface ScheduleIdBackWriter {
        /**
         * 回写 schedule_id 到 E10 会议记录。
         *
         * @param meetingId  E10 会议 ID
         * @param scheduleId 企业微信日程 ID
         * @author DuJiang
         */
        void write(String meetingId, String scheduleId);
    }

    public MeetingSyncService(WeComConfig config) {
        this(config, null);
    }

    public MeetingSyncService(WeComConfig config, UserIdMapper userIdMapper) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        this.config = config;
        this.client = new WeComScheduleClient(config);
        this.userIdMapper = userIdMapper == null ? createDefaultMapper(config, client) : userIdMapper;
        log.info("[MeetingSyncService] 初始化完成, useridMode={}, 映射器={}",
                config.getUseridMode(), this.userIdMapper.getClass().getSimpleName());
    }

    /**
     * 根据配置创建默认的 userid 映射器。
     *
     * @author DuJiang
     */
    private static UserIdMapper createDefaultMapper(WeComConfig config, WeComScheduleClient client) {
        String mode = config.getUseridMode();
        if ("phone".equalsIgnoreCase(mode)) {
            return new PhoneUserIdMapper(config, client);
        }
        if ("mobile".equalsIgnoreCase(mode)) {
            return new MobileUserIdMapper(client);
        }
        return new DirectUserIdMapper();
    }

    public WeComScheduleClient getClient() {
        return client;
    }

    public void setScheduleIdBackWriter(ScheduleIdBackWriter scheduleIdBackWriter) {
        this.scheduleIdBackWriter = scheduleIdBackWriter;
    }

    /**
     * 同步会议日程：已存在则更新，否则创建。
     *
     * @param meeting 会议信息
     * @return 企业微信 API 返回结果（成功时 scheduleId 有值）
     * @author DuJiang
     */
    public WeComResult syncMeeting(MeetingInfo meeting) {
        log.info("[MeetingSyncService] syncMeeting 入口, meetingId={}, title={}, startTime={}, endTime={}, attendees={}",
                meeting.getMeetingId(), meeting.getTitle(), meeting.getStartTime(), meeting.getEndTime(), meeting.getAttendees());
        String err = meeting.validate();
        if (err != null) {
            log.warn("[MeetingSyncService] syncMeeting 参数校验失败(meetingId={}): {}", meeting.getMeetingId(), err);
            return WeComResult.failure(-2, err, null);
        }
        List<String> userids = resolveAttendees(meeting);
        log.info("[MeetingSyncService] syncMeeting 参会人映射完成(meetingId={}), userids={}", meeting.getMeetingId(), userids);

        String calId = getOrCreateCalendar(meeting, userids);
        if (calId == null) {
            log.error("[MeetingSyncService] syncMeeting 创建/获取共享日历失败(meetingId={}), 终止同步", meeting.getMeetingId());
            return WeComResult.failure(-3, "创建/获取共享日历失败，请检查日历权限", null);
        }

        MeetingBind bind = binds.computeIfAbsent(meeting.getMeetingId(), k -> new MeetingBind(calId));
        bind.scheduleId = bind.scheduleId == null ? "" : bind.scheduleId;

        WeComResult result;
        if (bind.scheduleId.isEmpty()) {
            log.info("[MeetingSyncService] syncMeeting 首次创建日程(meetingId={}, calId={})", meeting.getMeetingId(), calId);
            result = client.addSchedule(calId, meeting.getTitle(), meeting.getDescription(),
                    meeting.getStartTime(), meeting.getEndTime(), meeting.getLocation(),
                    userids, config.getRemindBeforeSecs());
            if (result.isOk()) {
                bind.scheduleId = result.getScheduleId();
                log.info("[MeetingSyncService] syncMeeting 日程创建成功(meetingId={}, scheduleId={})", meeting.getMeetingId(), result.getScheduleId());
                safeBackWrite(meeting.getMeetingId(), result.getScheduleId());
            } else {
                log.error("[MeetingSyncService] syncMeeting 日程创建失败(meetingId={}): {}", meeting.getMeetingId(), result);
            }
        } else {
            log.info("[MeetingSyncService] syncMeeting 复用已存在日程并更新(meetingId={}, scheduleId={})", meeting.getMeetingId(), bind.scheduleId);
            result = client.updateSchedule(bind.scheduleId, calId, meeting.getTitle(),
                    meeting.getDescription(), meeting.getStartTime(), meeting.getEndTime(),
                    meeting.getLocation(), userids, config.getRemindBeforeSecs());
        }
        log.info("[MeetingSyncService] syncMeeting 出口, meetingId={}, result={}", meeting.getMeetingId(), result);
        return result;
    }

    /**
     * 更新会议日程（改期 / 变更参会人）。会议从未同步过则直接创建。
     *
     * @param meeting 会议信息
     * @return 调用结果
     * @author DuJiang
     */
    public WeComResult updateMeeting(MeetingInfo meeting) {
        log.info("[MeetingSyncService] updateMeeting 入口, meetingId={}, title={}, startTime={}, endTime={}, attendees={}",
                meeting.getMeetingId(), meeting.getTitle(), meeting.getStartTime(), meeting.getEndTime(), meeting.getAttendees());
        String err = meeting.validate();
        if (err != null) {
            log.warn("[MeetingSyncService] updateMeeting 参数校验失败(meetingId={}): {}", meeting.getMeetingId(), err);
            return WeComResult.failure(-2, err, null);
        }
        MeetingBind bind = binds.get(meeting.getMeetingId());
        if (bind == null || bind.scheduleId == null || bind.scheduleId.isEmpty()) {
            log.info("[MeetingSyncService] updateMeeting 未找到已有日程(meetingId={}), 转创建", meeting.getMeetingId());
            return syncMeeting(meeting);
        }
        List<String> userids = resolveAttendees(meeting);
        log.info("[MeetingSyncService] updateMeeting 更新已有日程(meetingId={}, scheduleId={}), userids={}",
                meeting.getMeetingId(), bind.scheduleId, userids);
        WeComResult result = client.updateSchedule(bind.scheduleId, bind.calId, meeting.getTitle(),
                meeting.getDescription(), meeting.getStartTime(), meeting.getEndTime(),
                meeting.getLocation(), userids, config.getRemindBeforeSecs());
        log.info("[MeetingSyncService] updateMeeting 出口, meetingId={}, result={}", meeting.getMeetingId(), result);
        return result;
    }

    /**
     * 取消会议日程（删除企业微信日程）。
     *
     * @param meetingId E10 会议 ID
     * @return 调用结果；会议从未同步过时返回成功（无操作）
     * @author DuJiang
     */
    public WeComResult cancelMeeting(String meetingId) {
        log.info("[MeetingSyncService] cancelMeeting 入口, meetingId={}", meetingId);
        if (meetingId == null || meetingId.trim().isEmpty()) {
            log.warn("[MeetingSyncService] cancelMeeting 参数校验失败: meetingId 不能为空");
            return WeComResult.failure(-2, "meetingId 不能为空", null);
        }
        MeetingBind bind = binds.get(meetingId);
        if (bind == null || bind.scheduleId == null || bind.scheduleId.isEmpty()) {
            log.info("[MeetingSyncService] cancelMeeting 该会议从未同步过日程(meetingId={}), 无操作返回成功", meetingId);
            return WeComResult.success(null, null, "no schedule, skip");
        }
        log.info("[MeetingSyncService] cancelMeeting 删除日程(meetingId={}, scheduleId={})", meetingId, bind.scheduleId);
        WeComResult result = client.deleteSchedule(bind.scheduleId);
        if (result.isOk()) {
            binds.remove(meetingId);
            log.info("[MeetingSyncService] cancelMeeting 日程删除成功并清除缓存(meetingId={})", meetingId);
        } else {
            log.error("[MeetingSyncService] cancelMeeting 日程删除失败(meetingId={}): {}", meetingId, result);
        }
        return result;
    }

    /**
     * 清理进程内缓存（运维 / 测试用）。
     *
     * @author DuJiang
     */
    public void clearCache() {
        int size = binds.size();
        binds.clear();
        log.info("[MeetingSyncService] 已清理进程内绑定缓存, 清理前大小={}", size);
    }

    /**
     * 参会人 E10 账号 → 企微 userid 批量映射；映射缺失者告警并剔除，不阻断整体同步。
     *
     * @author DuJiang
     */
    private List<String> resolveAttendees(MeetingInfo meeting) {
        List<String> userids = new ArrayList<>();
        for (String userId : meeting.getAttendees()) {
            if (userId == null || userId.trim().isEmpty()) {
                log.info("[MeetingSyncService] 参会人为空，跳过(meetingId={})", meeting.getMeetingId());
                continue;
            }
            String wecomUserid = userIdMapper.map(userId.trim());
            if (wecomUserid == null || wecomUserid.trim().isEmpty()) {
                log.warn("[MeetingSyncService] 告警：E10用户ID[{}] 未映射到企业微信 userid，已跳过该参会人（meetingId={}）", userId, meeting.getMeetingId());
                continue;
            }
            userids.add(wecomUserid);
        }
        return userids;
    }

    /**
     * 按会议获取或创建共享日历（首次创建后按 meetingId 缓存 cal_id）。
     *
     * @author DuJiang
     */
    private String getOrCreateCalendar(MeetingInfo meeting, List<String> userids) {
        MeetingBind existing = binds.get(meeting.getMeetingId());
        if (existing != null && existing.calId != null) {
            log.info("[MeetingSyncService] 复用已有共享日历(meetingId={}, calId={})", meeting.getMeetingId(), existing.calId);
            return existing.calId;
        }
        String title = config.getCalendarTitle().replace("{meetingId}", meeting.getMeetingId());
        log.info("[MeetingSyncService] 创建共享日历(meetingId={}, title={})", meeting.getMeetingId(), title);
        WeComResult result = client.addCalendar(title, config.getCalendarDescription(), userids);
        if (!result.isOk()) {
            log.error("[MeetingSyncService] 创建共享日历失败: {}", result);
            return null;
        }
        log.info("[MeetingSyncService] 共享日历创建成功(meetingId={}, calId={})", meeting.getMeetingId(), result.getCalId());
        return result.getCalId();
    }

    /**
     * 安全执行 schedule_id 回写，回写异常仅告警不阻断主流程。
     *
     * @author DuJiang
     */
    private void safeBackWrite(String meetingId, String scheduleId) {
        try {
            scheduleIdBackWriter.write(meetingId, scheduleId);
            log.info("[MeetingSyncService] schedule_id 回写成功(meetingId={}, scheduleId={})", meetingId, scheduleId);
        } catch (Exception e) {
            log.error("[MeetingSyncService] 回写 schedule_id 失败(meetingId={}, scheduleId={}): {}", meetingId, scheduleId, e.getMessage());
        }
    }
}
