package com.weaver.seconddev.wecom.util;

import lombok.extern.slf4j.Slf4j;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
public class Util {
    private static Random random = new Random();

    public Util() {
    }

    public static String null2s(String s, String def) {
        return s != null && !s.equals("") ? s : (def == null ? "" : def);
    }

    public static String null2String(Object obj, String def) {
        return obj == null ? def : obj.toString();
    }

    public static String null2String(Object obj) {
        return null2String(obj, "");
    }

    public static int getIntValue(Object obj, int def) {
        try {
            return Integer.parseInt(null2String(obj));
        } catch (Exception exception) {
            return def;
        }
    }

    public static int getIntValue(Object obj) {
        return getIntValue(obj, -1);
    }

    public static Long getLongValue(Object obj) {
        return getLongValue(null2String(obj), -1L);
    }

    public static Long getLongValue(Object obj, long def) {
        try {
            return Long.parseLong(null2String(obj));
        } catch (Exception exception) {
            return def;
        }
    }

    public static List<String> splitString2List(String input, String delim) {
        return splitString2List(input, delim, -1);
    }

    public static List<String> splitString2List(String input, String delim, int limit) {
        if (isEmpty(input)) {
            return new ArrayList();
        } else {
            int delimLeng = delim.length();
            int off = 0;
            boolean limited = limit > 0;

            ArrayList list;
            int next;
            for (list = new ArrayList(); (next = input.indexOf(delim, off)) != -1; off = next + delimLeng) {
                if (limited && list.size() >= limit - 1) {
                    list.add(input.substring(off));
                    off = input.length();
                    break;
                }

                list.add(input.substring(off, next));
            }

            if (off == 0) {
                list.add(input);
                return list;
            } else {
                if (!limited || list.size() < limit) {
                    list.add(input.substring(off));
                }

                int resultSize = list.size();
                if (limit == 0) {
                    while (resultSize > 0 && ((String) list.get(resultSize - 1)).length() == 0) {
                        --resultSize;
                    }
                }

                return list.subList(0, resultSize);
            }
        }
    }

    public static double getDoubleValue(String v) {
        return getDoubleValue(v, -1.0);
    }

    public static double getDoubleValue(String v, double def) {
        try {
            return Double.parseDouble(v);
        } catch (Exception exception) {
            return def;
        }
    }

    public static boolean isEmpty(Object obj) {
        if (obj == null) {
            return true;
        } else if (obj.getClass().isArray()) {
            return ((Object[]) ((Object[]) obj)).length == 0;
        } else if (obj instanceof Collection) {
            return ((Collection) obj).isEmpty();
        } else {
            return obj instanceof Map ? ((Map) obj).isEmpty() : obj.toString().equals("");
        }
    }

    public static boolean isNotEmpty(Object obj) {
        return !isEmpty(obj);
    }

    public static String encode(String str) {
        try {
            return URLEncoder.encode(str, "UTF-8").replaceAll("\\+", "%20");
        } catch (UnsupportedEncodingException exception) {
            exception.printStackTrace();
            return str;
        }
    }

    public static String getUUID() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }

    /**
     * 从参会人元素中提取账号标识（兼容 String 与 Map / JSONObject）。
     *
     * <p>元素可能来自会议模块的不同传参形态：</p>
     * <ul>
     *   <li>String：直接视为账号（工号 / 登录名 / 手机号）</li>
     *   <li>Map / JSONObject：按常见键名依次取值（account / loginid / loginName / username / userid / workcode / mobile），
     *       命中任一非空值即返回；全未命中则回退 toString</li>
     * </ul>
     *
     * @param raw 参会人元素
     * @return 账号标识；入参为 null 或空返回空串
     * @author DuJiang
     */
    public static String getAccountFromAttendee(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof String) {
            return ((String) raw).trim();
        }
        if (raw instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) raw;
            String[] keys = {"account", "loginid", "loginName", "loginname", "username", "userid", "workcode", "mobile"};
            for (String key : keys) {
                Object v = map.get(key);
                if (v != null && !v.toString().trim().isEmpty()) {
                    return v.toString().trim();
                }
            }
        }
        String s = raw.toString();
        return s == null ? "" : s.trim();
    }

    public static String getUUIDStartsWithLetter() {
        String uuid;
        for (uuid = getUUID(); Character.isDigit(uuid.charAt(0)); uuid = getUUID()) {
        }

        return uuid;
    }

//    public static String getExceptionMsg(Throwable ex) {
//        String msg = "";
//        if (ex instanceof BindException) {
//            FieldError fieldError = ((BindException)ex).getFieldError();
//            msg = fieldError == null ? ex.getMessage() : fieldError.getField() + fieldError.getDefaultMessage();
//        } else {
//            Throwable exCause = ex.getCause();
//            msg = exCause == null ? ex.getMessage() : getExceptionMsg(exCause);
//        }
//
//        return msg;
//    }

    public static boolean isPrimitive(Class<?> cl) {
        return cl == Boolean.class || cl == Character.class || cl == Byte.class || cl == Short.class || cl == Integer.class || cl == Long.class || cl == Float.class || cl == Double.class || cl == String.class;
    }

    public static String getRandom() {
        int randomInt;
        for (randomInt = 1000000000 + random.nextInt(1000000000); randomInt == 0; randomInt = 1000000000 + random.nextInt(1000000000)) {
        }

        return String.valueOf(randomInt);
    }

    public static ArrayList TokenizerString(String str, String dim) {
        return TokenizerString(str, dim, false);
    }

    public static ArrayList TokenizerString(String str, String dim, boolean returndim) {
        str = null2String(str);
        dim = null2String(dim);
        ArrayList strlist = new ArrayList();
        StringTokenizer strtoken = new StringTokenizer(str, dim, returndim);

        while (strtoken.hasMoreTokens()) {
            strlist.add(strtoken.nextToken());
        }

        return strlist;
    }

    public static String toHtmlForSplitPage(String s) {
        char[] c = s.toCharArray();
        int i = 0;
        StringBuffer buf = new StringBuffer();

        while (i < c.length) {
            char ch = c[i++];
            if (ch == '\'') {
                buf.append("\\'");
            } else if (ch == '<') {
                buf.append("&lt;");
            } else if (ch == '>') {
                buf.append("&gt;");
            } else if (ch == '&') {
                buf.append("&amp;");
            } else {
                buf.append(ch);
            }
        }

        return buf.toString();
    }

    public static boolean isExcuteFile(String fileName) {
        if (fileName == null) {
            return false;
        } else {
            boolean returnValue = false;
            fileName = fileName.replaceAll("(\u0000|::).*$", "");
            int extNamePos = fileName.lastIndexOf(".");
            if (extNamePos != -1) {
                String extName = null2String(fileName.substring(extNamePos + 1));
                if (extName.equalsIgnoreCase("jsp") || extName.equalsIgnoreCase("php") || extName.equalsIgnoreCase("jspx")) {
                    returnValue = true;
                }
            }

            return returnValue;
        }
    }

    public static ArrayList matchAll(String str, String reg, int groupid, int num) {
        ArrayList returnList = new ArrayList();

        try {
            Pattern pattern = Pattern.compile(reg);
            Matcher matcher = pattern.matcher(str);

            for (int i = 0; matcher.find(); ++i) {
                String group = matcher.group(groupid);
                returnList.add(group);
                if (num != -1 && i >= num - 1) {
                    break;
                }
            }
        } catch (Exception exception) {
            log.error("err", exception);
        }

        return returnList;
    }

    public static <V> List<Map<String, V>> getCaseInsensitiveMapList(List<Map<String, V>> list) {
        List<Map<String, V>> resultList = new ArrayList();
        list.forEach((e) -> {
            resultList.add(getCaseInsensitiveMap(e));
        });
        return resultList;
    }

    public static <V> Map<String, V> getCaseInsensitiveMap(Map<String, V> map) {
        TreeMap<String, V> result = new TreeMap(String.CASE_INSENSITIVE_ORDER);
        result.putAll(map);
        return result;
    }
}

