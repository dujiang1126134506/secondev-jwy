package com.weaver.seconddev.wecom.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.weaver.common.base.entity.result.WeaResult;
import com.weaver.seconddev.wecom.config.WeComConfig;
import com.weaver.seconddev.wecom.model.MeetingInfo;
import com.weaver.seconddev.wecom.model.WeComResult;
import com.weaver.seconddev.wecom.service.MeetingSyncService;
import com.weaver.seconddev.wecom.util.Util;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后端二开 Controller：E10 会议 → 企业微信 OA 日程同步接口。
 *
 * <p>遵循泛微 E10 后端二次开发规范：</p>
 * <ul>
 *   <li>二开代码置于 {@code com.weaver.seconddev} 包（规范 2.1）</li>
 *   <li>接口路由为 {@code /api/secondev/xxx}（需登录访问，规范 2.2）</li>
 *   <li>返回类型统一为 {@code com.weaver.common.base.entity.result.WeaResult}（规范 2.3）</li>
 * </ul>
 *
 * <p>E10 会议模块的「接口动作 / 自定义动作」配置为调用本 Controller 的 HTTP 地址即可，
 * 例如 {@code POST /api/secondev/wecom/syncMeeting}，请求体为 JSON 会议字段。</p>
 *
 * <p>暴露接口：</p>
 * <ul>
 *   <li>{@link #syncMeeting(Map)}   创建或更新会议日程（按 meetingId 幂等复用共享日历）</li>
 *   <li>{@link #updateMeeting(Map)} 变更日程（时间 / 参会人），等价于幂等更新</li>
 *   <li>{@link #cancelMeeting(Map)} 取消会议日程（按 meetingId 删除对应日程）</li>
 *   <li>{@link #health()}           健康检查</li>
 * </ul>
 *
 * <p>请求体 JSON 字段：</p>
 * <ul>
 *   <li>meetingId : String（必填，E10 会议唯一标识，用于幂等与共享日历命名）</li>
 *   <li>title : String（必填）</li>
 *   <li>description : String（可选）</li>
 *   <li>startTime / endTime : 秒级时间戳（Number / 字符串，必填）</li>
 *   <li>location : String（可选）</li>
 *   <li>attendees : List&lt;String&gt; / List&lt;Object&gt; 或逗号分隔字符串或 JSON 数组串（可选，元素可为账号字符串或对象）</li>
 * </ul>
 *
 * @author DuJiang
 */
@Slf4j
@RestController
@RequestMapping("/api/secondev/wecom")
public class MeetingWeComController {

    private final MeetingSyncService syncService;

    /**
     * 无参构造由 Spring 容器调用；在此完成配置加载与同步服务装配。
     *
     * @author DuJiang
     */
    public MeetingWeComController() {
        WeComConfig config = WeComConfig.load();
        this.syncService = new MeetingSyncService(config);
        log.info("[MeetingWeComController] 初始化完成, useridMode={}", config.getUseridMode());
    }

    /**
     * 创建或更新会议日程（幂等）。
     *
     * @param params 会议字段 JSON
     * @return 标准 WeaResult，data 中携带 success/errcode/errmsg/scheduleId/calId
     * @author DuJiang
     */
    @RequestMapping(value = "/syncMeeting", method = {RequestMethod.GET, RequestMethod.POST})
    public WeaResult<Map<String, Object>> syncMeeting(HttpServletRequest request) {
        Map<String, Object> params = resolveParams(request);
        log.info("[MeetingWeComController] 接口入口 syncMeeting, method={}, 请求参数: {}", request.getMethod(), JSON.toJSONString(params));
        long start = System.currentTimeMillis();
        try {
            MeetingInfo meeting = fromParams(params);
            WeComResult result = syncService.syncMeeting(meeting);
            WeaResult<Map<String, Object>> resp = toWeaResult(result);
            log.info("[MeetingWeComController] 接口出口 syncMeeting, meetingId={}, 耗时={}ms, 响应: {}", meeting.getMeetingId(), System.currentTimeMillis() - start, JSON.toJSONString(resp));
            return resp;
        } catch (Exception e) {
            log.error("[MeetingWeComController] syncMeeting 异常: {}", e.getMessage(), e);
            return WeaResult.fail(-1, e.getMessage());
        }
    }

    /**
     * 变更会议日程（改期 / 调整参会人），幂等等价于 syncMeeting。
     *
     * @param request 请求（GET 取 query 参数，POST 取 JSON body）
     * @return 标准 WeaResult
     * @author DuJiang
     */
    @RequestMapping(value = "/updateMeeting", method = {RequestMethod.GET, RequestMethod.POST})
    public WeaResult<Map<String, Object>> updateMeeting(HttpServletRequest request) {
        Map<String, Object> params = resolveParams(request);
        log.info("[MeetingWeComController] 接口入口 updateMeeting, method={}, 请求参数: {}", request.getMethod(), JSON.toJSONString(params));
        long start = System.currentTimeMillis();
        try {
            MeetingInfo meeting = fromParams(params);
            WeComResult result = syncService.updateMeeting(meeting);
            WeaResult<Map<String, Object>> resp = toWeaResult(result);
            log.info("[MeetingWeComController] 接口出口 updateMeeting, meetingId={}, 耗时={}ms, 响应: {}", meeting.getMeetingId(), System.currentTimeMillis() - start, JSON.toJSONString(resp));
            return resp;
        } catch (Exception e) {
            log.error("[MeetingWeComController] updateMeeting 异常: {}", e.getMessage(), e);
            return WeaResult.fail(-1, e.getMessage());
        }
    }

    /**
     * 取消会议日程（用于会议取消场景）。
     *
     * @param request 请求（GET 取 query 参数，POST 取 JSON body）
     * @return 标准 WeaResult
     * @author DuJiang
     */
    @RequestMapping(value = "/cancelMeeting", method = {RequestMethod.GET, RequestMethod.POST})
    public WeaResult<Map<String, Object>> cancelMeeting(HttpServletRequest request) {
        Map<String, Object> params = resolveParams(request);
        log.info("[MeetingWeComController] 接口入口 cancelMeeting, method={}, 请求参数: {}", request.getMethod(), JSON.toJSONString(params));
        long start = System.currentTimeMillis();
        try {
            String meetingId = Util.null2String(params.get("meetingId"));
            if (meetingId == null || meetingId.isEmpty()) {
                log.warn("[MeetingWeComController] cancelMeeting 参数校验失败: meetingId 不能为空");
                return WeaResult.fail(-1, "meetingId 不能为空");
            }
            WeComResult result = syncService.cancelMeeting(meetingId);
            WeaResult<Map<String, Object>> resp = toWeaResult(result);
            log.info("[MeetingWeComController] 接口出口 cancelMeeting, meetingId={}, 耗时={}ms, 响应: {}", meetingId, System.currentTimeMillis() - start, JSON.toJSONString(resp));
            return resp;
        } catch (Exception e) {
            log.error("[MeetingWeComController] cancelMeeting 异常: {}", e.getMessage(), e);
            return WeaResult.fail(-1, e.getMessage());
        }
    }

    /**
     * 健康检查接口。
     *
     * @return 标准 WeaResult，data 中携带 status=UP
     * @author DuJiang
     */
    @GetMapping("/health")
    public WeaResult<Map<String, Object>> health() {
        Map<String, Object> data = new HashMap<>(4);
        data.put("status", "UP");
        return WeaResult.success(data);
    }

    /**
     * 将企微同步结果封装为标准 WeaResult。
     *
     * @author DuJiang
     */
    private WeaResult<Map<String, Object>> toWeaResult(WeComResult result) {
        Map<String, Object> data = new HashMap<>(8);
        data.put("success", result.isOk());
        data.put("errcode", result.getErrcode());
        data.put("errmsg", result.getErrmsg());
        data.put("scheduleId", result.getScheduleId());
        data.put("calId", result.getCalId());
        if (result.isOk()) {
            return WeaResult.success(data);
        }
        return WeaResult.fail(result.getErrcode(), result.getErrmsg());
    }

    /**
     * 统一解析请求参数：GET 取 query 参数；POST 取 JSON body（若有）并覆盖同名 query 参数；
     * 同时兼容 form-urlencoded 表单（Servlet 容器会并入 parameterMap）。
     *
     * @param request HTTP 请求
     * @return 合并后的参数 Map
     * @author DuJiang
     */
    private Map<String, Object> resolveParams(HttpServletRequest request) {
        Map<String, Object> params = new HashMap<>();
        // ① query string / form 参数
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            String[] values = entry.getValue();
            params.put(entry.getKey(), (values != null && values.length > 0) ? values[0] : "");
        }
        // ② POST JSON body（覆盖同名参数）
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String body = readBody(request);
            if (body != null && !body.trim().isEmpty() && body.trim().startsWith("{")) {
                try {
                    Map<String, Object> jsonMap = JSON.parseObject(body, Map.class);
                    if (jsonMap != null) {
                        params.putAll(jsonMap);
                    }
                } catch (Exception e) {
                    log.warn("[MeetingWeComController] 解析 JSON body 失败，忽略: {}", e.getMessage());
                }
            }
        }
        return params;
    }

    /**
     * 读取请求体内容（读取后原流不可再用，仅适用于一次性消费场景）。
     *
     * @author DuJiang
     */
    private String readBody(HttpServletRequest request) {
        try (BufferedReader reader = request.getReader()) {
            if (reader == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[MeetingWeComController] 读取请求体失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将请求体字段 Map 转为会议对象。
     *
     * @author DuJiang
     */
    private MeetingInfo fromParams(Map<String, Object> params) {
        MeetingInfo meeting = new MeetingInfo();
        meeting.setMeetingId(Util.null2String(params.get("meetingId")));
        meeting.setTitle(Util.null2String(params.get("title")));
        meeting.setDescription(Util.null2String(params.get("description")));
        meeting.setLocation(Util.null2String(params.get("location")));
        meeting.setStartTime(seconds(params.get("startTime")));
        meeting.setEndTime(seconds(params.get("endTime")));
        meeting.setAttendees(parseAttendees(params.get("attendees")));
        return meeting;
    }

    /**
     * 解析参会人字段（兼容 List 与逗号分隔字符串 / JSON 数组串）。
     *
     * <p>元素为 E10 用户 ID 字符串，后续由映射层通过工具类 {@code UserContext}
     * 获取手机号后换取企业微信 userid。</p>
     *
     * @author DuJiang
     */
    private List<String> parseAttendees(Object raw) {
        List<String> list = new ArrayList<>();
        if (raw == null) {
            return list;
        }
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item != null && !String.valueOf(item).trim().isEmpty()) {
                    list.add(String.valueOf(item).trim());
                }
            }
        } else {
            String text = String.valueOf(raw);
            if (text.startsWith("[")) {
                List<Object> parsed = JSON.parseArray(text);
                for (Object item : parsed) {
                    if (item != null && !String.valueOf(item).trim().isEmpty()) {
                        list.add(String.valueOf(item).trim());
                    }
                }
            } else {
                for (String part : text.split(",")) {
                    if (!part.trim().isEmpty()) {
                        list.add(part.trim());
                    }
                }
            }
        }
        return list;
    }

    /**
     * 转换为秒级时间戳（兼容数字时间戳与日期时间字符串，如 2026-08-21 10:45:00）。
     *
     * @author DuJiang
     */
    private Long seconds(Object raw) {
        Long value = Util.toSeconds(raw);
        if (value == null && raw != null && !String.valueOf(raw).trim().isEmpty()) {
            throw new IllegalArgumentException("时间字段无法解析: " + raw);
        }
        return value;
    }

}
