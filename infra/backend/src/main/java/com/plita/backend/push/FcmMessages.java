package com.plita.backend.push;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Тела сообщений FCM HTTP v1 (POST /v1/projects/{project}/messages:send). Без внешних зависимостей.
 *
 * <p>В {@code data} все значения - строки (требование FCM). По {@code data.type} приложение понимает,
 * что это тревога, и открывает «красный экран»; {@code notification} показывается, даже если приложение закрыто.
 */
public final class FcmMessages {

    private FcmMessages() {
    }

    public static String alarmTitle(String homeName) {
        return "ТРЕВОГА" + (homeName == null || homeName.isBlank() ? "" : ": " + homeName);
    }

    public static String alarmBody(String reason, boolean commandSent) {
        String text = switch (reason) {
            case "radar_critical" -> "Датчик у плиты сообщил о критической ситуации.";
            case "dangerous_dynamics" -> "Плита включена, температура или VOC растут, а рядом никого нет.";
            case "process_thresholds" -> "Показатели вышли за пределы нормы для текущего процесса.";
            case "sensor_lost" -> "Пропала связь с датчиком при включённой плите.";
            default -> "Опасная ситуация у плиты.";
        };
        return text + (commandSent ? " Плита выключена." : " Плита НЕ выключена автоматически.");
    }

    /** Сообщение о тревоге на одно устройство (token). */
    public static Map<String, Object> alarm(String token, long homeId, String homeName, String reason,
                                            boolean commandSent, String at, String androidChannelId) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "alarm");
        data.put("home_id", Long.toString(homeId));
        data.put("reason", reason);
        data.put("command_sent", Boolean.toString(commandSent));
        data.put("at", at);
        return message(token, alarmTitle(homeName), alarmBody(reason, commandSent), data, androidChannelId);
    }

    /** Проверочное сообщение для POST /api/v1/push/test. */
    public static Map<String, Object> test(String token, String androidChannelId) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "test");
        return message(token, "Контроль плиты", "Проверка push-уведомлений: всё работает.", data, androidChannelId);
    }

    static Map<String, Object> message(String token, String title, String body, Map<String, String> data,
                                       String androidChannelId) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", title);
        notification.put("body", body);

        Map<String, Object> androidNotification = new LinkedHashMap<>();
        androidNotification.put("sound", "default");
        if (androidChannelId != null && !androidChannelId.isBlank()) {
            androidNotification.put("channel_id", androidChannelId);
        }
        Map<String, Object> android = new LinkedHashMap<>();
        android.put("priority", "HIGH");
        android.put("notification", androidNotification);

        Map<String, Object> apns = new LinkedHashMap<>();
        apns.put("headers", Map.of("apns-priority", "10"));
        apns.put("payload", Map.of("aps", Map.of("sound", "default")));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", token);
        message.put("notification", notification);
        message.put("data", data);
        message.put("android", android);
        message.put("apns", apns);
        return Map.of("message", message);
    }
}
