# Android, Compose and serialization libraries provide consumer rules.
# JExcel creates its default logger with Class.forName(...).newInstance().
-keep class jxl.common.log.SimpleLogger {
    public <init>();
}
