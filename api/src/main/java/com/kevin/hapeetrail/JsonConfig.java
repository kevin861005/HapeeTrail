package com.kevin.hapeetrail;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class JsonConfig {

	/**
	 * 型別不對就是型別不對。Jackson 預設對純量做寬鬆轉型，那會讓契約的
	 * 「欄位型別不對 → 400 沒有 {@code code}」靜默失效（notes.md §2、§3）：
	 *
	 * <ul>
	 * <li>字串 → 數字：{@code "35.6"} 是字串，不是座標。座標被寬鬆解讀，錯的是便條落在哪裡。
	 * <li>小數 → 整數：{@code {"color": 1.5}} 會被截成 1 並回 200，而契約說非整數的代號
	 * 是**型別**錯誤，不是 {@code invalid_style_code}。
	 * <li>純量 → 字串：{@code {"content": 123}} 會被靜默轉成 {@code "123"} 並回 200，
	 * {@code {"audience": 5}} 會走到白名單比對而拿到 {@code invalid_audience}——那是業務錯誤的
	 * 形狀，但這是型別錯誤。反方向漏掉，這個類別就只守住了規則的一半。
	 * </ul>
	 */
	@Bean
	JsonMapperBuilderCustomizer strictScalarTypes() {
		return (builder) -> builder
			.withCoercionConfig(LogicalType.Float,
					(config) -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail))
			.withCoercionConfig(LogicalType.Integer, (config) -> config
				.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
			.withCoercionConfig(LogicalType.Textual, (config) -> config
				.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
	}

}
