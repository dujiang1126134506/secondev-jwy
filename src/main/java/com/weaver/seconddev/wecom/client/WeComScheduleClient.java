package com.weaver.seconddev.wecom.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.weaver.seconddev.wecom.config.WeComConfig;
import com.weaver.seconddev.wecom.model.WeComResult;
import com.weaver.seconddev.wecom.util.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 企业微信「OA 日程」API 客户端。
 *
 * <p>接口基于官方公开 API（基础域名 <code>https://qyapi.weixin.qq.com/cgi-bin/</code>）：</p>
 * <ul>
 *   <li>gettoken 获取 access_token（服务端缓存，过期前复用）</li>
 *   <li>oa/calendar/add 创建共享日历</li>
 *   <li>oa/schedule/add|update|del 创建 / 更新 / 删除日程</li>
 * </ul>
 *
 * <p>线程安全：token 缓存使用双检锁 + 可见性保证，所有方法可并发调用。</p>
 *
 * @author DuJiang
 */
@Slf4j
public class WeComScheduleClient {

    /** 企业微信错误码：不合法的 access_token（重新获取后重试一次） */
    private static final int ERR_INVALID_TOKEN = 40014;
    /** 企业微信错误码：缺少 access_token */
    private static final int ERR_MISSING_TOKEN = 41001;
    /** 企业微信错误码：接口调用超限 */
    private static final int ERR_RATE_LIMIT = 45009;

    private final WeComConfig config;

    /** 缓存的 access_token */
    private volatile String accessToken;
    /** token 过期时刻（毫秒） */
    private volatile long tokenExpireAt;
    /** 获取 token 的互斥锁 */
    private final ReentrantLock tokenLock = new ReentrantLock();

    public WeComScheduleClient(WeComConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        this.config = config;
    }

    /**
     * 获取 access_token（带缓存，过期自动刷新）。
     *
     * @return access_token
     * @author DuJiang
     */
    public String getToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return accessToken;
        }
        tokenLock.lock();
        try {
            // 双检：锁内再次确认是否已被其他线程刷新
            if (accessToken != null && System.currentTimeMillis() < tokenExpireAt) {
                return accessToken;
            }
            log.info("[WeComScheduleClient] access_token 缓存过期/缺失，重新获取");
            String url = config.getApiBase() + "/gettoken?corpid=" + urlEncode(config.getCorpid())
                    + "&corpsecret=" + urlEncode(config.getCorpsecret());
            String body = HttpClientUtil.get(url);
            JSONObject json = JSON.parseObject(body);
            if (json == null || json.getIntValue("errcode") != 0) {
                log.error("[WeComScheduleClient] 获取 access_token 失败: {}", body);
                throw new IllegalStateException("获取 access_token 失败: " + body);
            }
            String token = json.getString("access_token");
            int expiresIn = json.getIntValue("expires_in");
            accessToken = token;
            tokenExpireAt = System.currentTimeMillis()
                    + (expiresIn - config.getTokenSafeSecs()) * 1000L;
            log.info("[WeComScheduleClient] access_token 获取成功, 有效期={}s, 将在 {}ms 后过期刷新", expiresIn, expiresIn * 1000L - config.getTokenSafeSecs() * 1000L);
            return token;
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * 创建共享日历。
     *
     * @param title       日历标题
     * @param description 日历描述
     * @param shares      日历成员（企业微信 userid 列表），readonly=0 即读写
     * @return 调用结果（成功时 calId 有值）
     * @author DuJiang
     */
    public WeComResult addCalendar(String title, String description, List<String> shares) {
        JSONObject payload = new JSONObject();
        payload.put("title", title);
        payload.put("description", description);
        if (shares != null && !shares.isEmpty()) {
            JSONArray array = new JSONArray();
            for (String userId : shares) {
                JSONObject member = new JSONObject();
                member.put("userid", userId);
                member.put("readonly", 0);
                array.add(member);
            }
            payload.put("shares", array);
        }
        return doPost("oa/calendar/add", payload);
    }

    /**
     * 创建日程。
     *
     * @param calId     共享日历 ID
     * @param title     日程标题
     * @param desc      日程描述
     * @param startTime 开始时间（秒级时间戳）
     * @param endTime   结束时间（秒级时间戳）
     * @param location  会议地点
     * @param attendees 参会人 userid 列表
     * @param reminders 提醒提前量（秒），如 [3600, 600]
     * @return 调用结果（成功时 scheduleId 有值）
     * @author DuJiang
     */
    public WeComResult addSchedule(String calId, String title, String desc,
                                   long startTime, long endTime, String location,
                                   List<String> attendees, List<Integer> reminders) {
        JSONObject schedule = buildSchedule(null, calId, title, desc, startTime, endTime,
                location, attendees, reminders);
        JSONObject payload = new JSONObject();
        payload.put("schedule", schedule);
        return doPost("oa/schedule/add", payload);
    }

    /**
     * 更新日程（改期 / 变更参会人等）。
     *
     * @param scheduleId 待更新的日程 ID
     * @param calId      日历 ID
     * @param title      日程标题
     * @param desc       日程描述
     * @param startTime  开始时间（秒级时间戳）
     * @param endTime    结束时间（秒级时间戳）
     * @param location   会议地点
     * @param attendees  参会人 userid 列表
     * @param reminders  提醒提前量（秒）
     * @return 调用结果
     * @author DuJiang
     */
    public WeComResult updateSchedule(String scheduleId, String calId, String title, String desc,
                                      long startTime, long endTime, String location,
                                      List<String> attendees, List<Integer> reminders) {
        JSONObject schedule = buildSchedule(scheduleId, calId, title, desc, startTime, endTime,
                location, attendees, reminders);
        JSONObject payload = new JSONObject();
        payload.put("schedule", schedule);
        return doPost("oa/schedule/update", payload);
    }

    /**
     * 删除日程。
     *
     * @param scheduleId 日程 ID
     * @return 调用结果
     * @author DuJiang
     */
    public WeComResult deleteSchedule(String scheduleId) {
        JSONObject payload = new JSONObject();
        payload.put("schedule_id", scheduleId);
        return doPost("oa/schedule/del", payload);
    }

    /**
     * 根据手机号查询企业微信 userid（用于 E10 按手机号绑定企微的场景）。
     *
     * <p>调用接口：<code>POST /user/get_by_mobile</code>，
     * 需确保自建应用拥有「通讯录」读取权限。</p>
     *
     * @param mobile 手机号
     * @return 成功时返回 userid；失败返回 null
     * @author DuJiang
     */
    public String getUserIdByMobile(String mobile) {
        if (mobile == null || mobile.trim().isEmpty()) {
            log.warn("[WeComScheduleClient] getUserIdByMobile 入参手机号为空，返回 null");
            return null;
        }
        String cleanMobile = mobile.trim();
        log.info("[WeComScheduleClient] getUserIdByMobile 入口, mobile={}", cleanMobile);
        JSONObject payload = new JSONObject();
        payload.put("mobile", cleanMobile);
        WeComResult result = doPost("user/get_by_mobile", payload, true);
        if (result.isOk()) {
            JSONObject json = JSON.parseObject(result.getRaw());
            String userid = json == null ? null : json.getString("userid");
            log.info("[WeComScheduleClient] getUserIdByMobile 出口, mobile={}, userid={}", cleanMobile, userid);
            return userid;
        }
        log.error("[WeComScheduleClient] 根据手机号查询 userid 失败: {}", result);
        return null;
    }

    /**
     * 构建日程对象。
     *
     * @author DuJiang
     */
    private JSONObject buildSchedule(String scheduleId, String calId, String title, String desc,
                                     long startTime, long endTime, String location,
                                     List<String> attendees, List<Integer> reminders) {
        JSONObject schedule = new JSONObject();
        if (scheduleId != null) {
            schedule.put("schedule_id", scheduleId);
        }
        schedule.put("calendar_id", calId);
        schedule.put("title", title);
        if (desc != null && !desc.isEmpty()) {
            schedule.put("description", desc);
        }
        schedule.put("start_time", startTime);
        schedule.put("end_time", endTime);
        if (location != null && !location.isEmpty()) {
            schedule.put("location", location);
        }
        if (attendees != null && !attendees.isEmpty()) {
            JSONArray array = new JSONArray();
            for (String userId : attendees) {
                JSONObject attendee = new JSONObject();
                attendee.put("userid", userId);
                // 3 = 待定（未回应）
                attendee.put("response_status", 3);
                array.add(attendee);
            }
            schedule.put("attendees", array);
        }
        if (reminders != null && !reminders.isEmpty()) {
            JSONArray array = new JSONArray();
            for (Integer secs : reminders) {
                JSONObject reminder = new JSONObject();
                reminder.put("is_remind", 1);
                reminder.put("remind_before_event_secs", secs);
                array.add(reminder);
            }
            schedule.put("reminders", array);
        }
        return schedule;
    }

    /**
     * 执行 POST 请求并解析统一响应；遇 40014/41001 自动刷新 token 重试一次。
     *
     * @param api    接口路径（不含域名），如 oa/schedule/add
     * @param payload 请求体
     * @return 解析后的结果
     * @author DuJiang
     */
    private WeComResult doPost(String api, JSONObject payload) {
        return doPost(api, payload, true);
    }

    /**
     * POST 请求内部实现。
     *
     * @author DuJiang
     */
    private WeComResult doPost(String api, JSONObject payload, boolean retry) {
        String url = config.getApiBase() + "/" + api + "?access_token=" + getToken();
        log.info("[WeComScheduleClient] 调用企微接口[{}], 请求体: {}", api, payload.toJSONString());
        long start = System.currentTimeMillis();
        String body = HttpClientUtil.postJson(url, payload.toJSONString());
        JSONObject json = JSON.parseObject(body);
        if (json == null) {
            log.error("[WeComScheduleClient] 企微接口[{}] 响应解析失败: {}", api, body);
            return WeComResult.failure(-1, "响应解析失败", body);
        }
        int errcode = json.getIntValue("errcode");
        if (retry && (errcode == ERR_INVALID_TOKEN || errcode == ERR_MISSING_TOKEN)) {
            // token 失效：强制刷新后重试一次
            tokenLock.lock();
            try {
                accessToken = null;
                tokenExpireAt = 0;
            } finally {
                tokenLock.unlock();
            }
            log.warn("[WeComScheduleClient] access_token 失效(errcode={})，已刷新并重试接口: {}", errcode, api);
            return doPost(api, payload, false);
        }
        if (errcode == ERR_RATE_LIMIT) {
            log.warn("[WeComScheduleClient] 接口调用超限，建议增加限流/退避重试: {}", api);
        }
        WeComResult result;
        if (errcode == 0) {
            result = WeComResult.success(json.getString("schedule_id"), json.getString("cal_id"), body);
        } else {
            result = WeComResult.failure(errcode, json.getString("errmsg"), body);
        }
        log.info("[WeComScheduleClient] 企微接口[{}] 调用完成, 耗时={}ms, errcode={}, errmsg={}, schedule_id={}, cal_id={}",
                api, System.currentTimeMillis() - start, errcode, json.getString("errmsg"),
                json.getString("schedule_id"), json.getString("cal_id"));
        return result;
    }

    /**
     * 简单 URL 编码（token 场景下参数为常规字符，兜底处理特殊字符）。
     *
     * @author DuJiang
     */
    private static String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
