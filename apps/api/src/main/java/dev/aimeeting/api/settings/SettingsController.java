package dev.aimeeting.api.settings;

import dev.aimeeting.api.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final SettingsRepository settings;

    public SettingsController(SettingsRepository settings) { this.settings = settings; }

    @GetMapping
    public AppSettings get() { return settings.get(); }

    @PatchMapping
    public AppSettings update(@Valid @RequestBody UpdateSettings request) {
        if (Boolean.TRUE.equals(request.paidApiEnabled())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAID_API_DISABLED",
                    "Paid API mode cannot be enabled in this build");
        }
        return settings.update(request.monthlyBudgetUsd(), request.warnAtPercent(),
                request.perMeetingMaxMinutes(), request.recordingRetentionDays(), request.revision());
    }

    public record UpdateSettings(
            @NotNull @DecimalMin("0.00") @DecimalMax("10000.00") @Digits(integer = 5, fraction = 2)
            BigDecimal monthlyBudgetUsd,
            @NotNull @Min(1) @Max(100) Integer warnAtPercent,
            @NotNull @Min(1) @Max(1440) Integer perMeetingMaxMinutes,
            @Min(1) @Max(3650) Integer recordingRetentionDays,
            @NotNull @Min(0) Integer revision,
            Boolean paidApiEnabled) {}
}
