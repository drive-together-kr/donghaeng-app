package app.donghaeng.identity.repo

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/*
 * timestamptz ↔ Instant. pgjdbc 는 Instant 를 바로 바인딩하지 못하므로 OffsetDateTime(UTC) 으로 넘긴다.
 */

internal fun Instant.toDb(): OffsetDateTime = atOffset(ZoneOffset.UTC)

internal fun ResultSet.instant(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

internal fun ResultSet.requireInstant(column: String): Instant =
    instant(column) ?: error("column $column is null")
