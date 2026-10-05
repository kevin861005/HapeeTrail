package com.kevin.hapeetrail.config;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 契約在 wire 上的共用常數。 */
public final class Wire {

	/** 時間戳格式：永遠六位小數、永遠 {@code Z}，不因秒數恰為整數而縮水（notes.md 共同約定）。 */
	public static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
		.withZone(ZoneOffset.UTC);

	private Wire() {
	}

}
