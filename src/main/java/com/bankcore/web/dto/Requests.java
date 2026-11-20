package com.bankcore.web.dto;

import com.bankcore.domain.AccountType;
import com.bankcore.domain.DocumentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Request bodies. Bean Validation annotations are checked automatically by {@code @Valid} in the controllers. */
public final class Requests {

    private Requests() {}

    public record OpenAccountRequest(@NotNull AccountType type) {}

    public record TransferRequest(
            @NotBlank @Size(max = 20) String sourceAccountNumber,
            @NotBlank @Size(max = 20) String destinationAccountNumber,
            @NotNull @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
            @Digits(integer = 17, fraction = 2, message = "amount may have at most 2 decimal places")
            BigDecimal amount,
            @Size(max = 255) String description) {}

    public record CashRequest(
            @NotNull @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
            @Digits(integer = 17, fraction = 2, message = "amount may have at most 2 decimal places")
            BigDecimal amount,
            @Size(max = 255) String description) {}

    public record KycSubmitRequest(
            @NotNull DocumentType documentType,
            @NotBlank @Size(max = 50) String documentNumber,
            @NotNull @Past LocalDate dateOfBirth,
            @NotBlank @Size(max = 300) String address) {}

    public record ReviewRequest(@Size(max = 500) String note) {}
}
