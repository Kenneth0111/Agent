package com.example.creator.analytics;

import java.util.regex.Pattern;

final class CommentText {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])(?:\\+?86[- ]?)?1[3-9][0-9]{9}(?![0-9])");

    static String redact(String text) {
        return PHONE.matcher(EMAIL.matcher(text).replaceAll("[邮箱]")).replaceAll("[手机号]");
    }
    private CommentText() { }
}
