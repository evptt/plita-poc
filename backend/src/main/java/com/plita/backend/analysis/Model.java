package com.plita.backend.analysis;

/**
 * Типы модели анализа собраны в одном файле: состояния Дома (рис. 5), вердикты,
 * причины тревоги, входные отсчёты и решения, которые анализатор отдаёт наружу.
 */
public final class Model {

    private Model() {
    }

    public enum HomeState {
        IDLE("Idle"), COOKING("Cooking"), CLASSIFIED("Classified"),
        NORMAL("Normal"), ALARM("Alarm"), RELAY_OFF("RelayOff");

        private final String value;

        HomeState(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static HomeState fromValue(String v) {
            for (HomeState s : values()) {
                if (s.value.equals(v)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("неизвестное состояние: " + v);
        }

        public boolean isActive() {
            return this == COOKING || this == CLASSIFIED || this == NORMAL;
        }
    }

    public enum Verdict {
        /** плита выключена, анализ не идёт */
        NONE,
        /** плита включена, процесс ещё не определён */
        UNDEFINED,
        /** НОРМА */
        NORMAL,
        /** ТРЕВОГА */
        ALARM
    }

    public enum AlarmReason {
        RADAR_CRITICAL("radar_critical"),
        DANGEROUS_DYNAMICS("dangerous_dynamics"),
        PROCESS_THRESHOLDS("process_thresholds"),
        SENSOR_LOST("sensor_lost");

        private final String value;

        AlarmReason(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** Отсчёт датчика; t - unix-время в секундах. */
    public record SensorSample(double t, double humidity, double temperature, double voc, int radar) {
    }

    /** Отсчёт реле; state: 1 = On, 0 = Off. */
    public record RelaySample(double t, double voltage, double current, int state) {
    }
}
