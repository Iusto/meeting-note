package dev.aimeeting.api.usage;

import dev.aimeeting.api.web.ApiException;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM")
            .withResolverStyle(ResolverStyle.STRICT);
    private final UsageService usage;

    public UsageController(UsageService usage) { this.usage = usage; }

    @GetMapping
    public MonthlyUsage get(@RequestParam(required = false) String month) {
        try {
            if (month != null && !month.matches("[0-9]{4}-(0[1-9]|1[0-2])")) invalidMonth();
            YearMonth requested = month == null ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(month, MONTH);
            if (requested.getYear() < 1) invalidMonth();
            return usage.get(requested);
        } catch (DateTimeException exception) {
            return invalidMonth();
        }
    }

    private MonthlyUsage invalidMonth() {
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MONTH", "Month must use YYYY-MM format");
    }
}
