package com.weaver.seconddev.wecom.model;

import java.io.Serializable;
import lombok.extern.slf4j.Slf4j;

/**
 * 企业微信 API 统一响应模型。
 *
 * @author DuJiang
 */
@Slf4j
public class WeComResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 企业微信返回码，0 表示成功 */
    private int errcode;

    /** 企业微信返回信息 */
    private String errmsg;

    /** 原始响应体 */
    private String raw;

    /** 成功时返回的日程 ID */
    private String scheduleId;

    /** 成功时返回的日历 ID */
    private String calId;

    public static WeComResult success(String scheduleId, String calId, String raw) {
        WeComResult result = new WeComResult();
        result.errcode = 0;
        result.errmsg = "ok";
        result.scheduleId = scheduleId;
        result.calId = calId;
        result.raw = raw;
        return result;
    }

    public static WeComResult failure(int errcode, String errmsg, String raw) {
        WeComResult result = new WeComResult();
        result.errcode = errcode;
        result.errmsg = errmsg;
        result.raw = raw;
        return result;
    }

    public boolean isOk() {
        return errcode == 0;
    }

    public int getErrcode() {
        return errcode;
    }

    public void setErrcode(int errcode) {
        this.errcode = errcode;
    }

    public String getErrmsg() {
        return errmsg;
    }

    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    public String getRaw() {
        return raw;
    }

    public void setRaw(String raw) {
        this.raw = raw;
    }

    public String getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
    }

    public String getCalId() {
        return calId;
    }

    public void setCalId(String calId) {
        this.calId = calId;
    }

    @Override
    public String toString() {
        return "WeComResult{errcode=" + errcode + ", errmsg='" + errmsg + "', scheduleId='" + scheduleId
                + "', calId='" + calId + "', raw='" + raw + "'}";
    }
}
