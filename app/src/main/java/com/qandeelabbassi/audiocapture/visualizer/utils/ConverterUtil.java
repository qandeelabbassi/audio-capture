package com.qandeelabbassi.audiocapture.visualizer.utils;

import org.joda.time.DateTime;

public class ConverterUtil {

    public static String convertMillsToTime(Long mill) {
        return new DateTime(mill).toString("mm:ss");
    }

}
