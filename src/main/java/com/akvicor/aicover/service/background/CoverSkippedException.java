package com.akvicor.aicover.service.background;

/** 后台任务在生成前发现文章已不符合条件，任务以跳过结束并展示该原因。 */
public class CoverSkippedException extends RuntimeException {

    public CoverSkippedException(String reason) {
        super(reason, null, false, false);
    }
}
