package com.plita.backend.analysis;

import com.plita.backend.analysis.Model.AlarmReason;
import com.plita.backend.analysis.Model.HomeState;
import com.plita.backend.analysis.Model.Verdict;

import java.util.Map;

/** Решение анализатора, которое исполняет Engine (БД, MQTT, WebSocket). */
public sealed interface Decision permits Decision.StateChanged, Decision.Alarm, Decision.Notice {

    /** Смена состояния Дома. */
    record StateChanged(HomeState state, Verdict verdict, String process, Map<String, Object> details)
            implements Decision {
    }

    /** ТРЕВОГА; cutoff - нужно ли отправить реле команду 0. */
    record Alarm(AlarmReason reason, Map<String, Object> details, boolean cutoff) implements Decision {
    }

    /** Уведомление: sensor_offline | sensor_online | relay_offline | relay_online. */
    record Notice(String kind, Map<String, Object> details) implements Decision {
    }
}
