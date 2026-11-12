package br.com.fiscal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

final class NfseModels {
    record CertificateInput(@NotBlank @Size(max=120) String label,
        @NotBlank @Pattern(regexp="[0-9]{11}|[0-9]{14}") String holderTaxId,
        @NotBlank @Size(max=1400000) String pfxBase64, @NotNull @Size(max=1024) String password) {
        @Override public String toString() { return "CertificateInput[PROTECTED]"; }
    }
    record ProxyInput(@NotNull UUID certificateId, @NotBlank @Size(max=200) String reference,
        @NotNull LocalDate validFrom, @NotNull LocalDate validTo, boolean canIssue, boolean canQuery, boolean canCancel, boolean active) {}
    record Proxy(UUID issuerId, UUID certificateId, String reference, LocalDate validFrom, LocalDate validTo,
        boolean canIssue, boolean canQuery, boolean canCancel, boolean active, String verificationStatus, OffsetDateTime verifiedAt) {}
    record Profile(@NotBlank @Pattern(regexp="[123]") String simplesOption,
        @Pattern(regexp="[123]|") String simplesAssessment,
        @NotBlank @Pattern(regexp="[0-6]|9") String specialRegime,
        @Size(max=15) String municipalRegistration,
        @NotBlank @Pattern(regexp="[0-9]{1,5}") String series,
        @Min(0) @Max(999999999999998L) long lastDpsNumber) {}
    record Address(@NotBlank @Pattern(regexp="[0-9]{7}") String municipality,
        @NotBlank @Pattern(regexp="[0-9]{8}") String zip,
        @NotBlank @Size(max=255) String street, @NotBlank @Size(max=60) String number,
        @NotBlank @Size(max=60) String neighborhood) {}
    record Recipient(@NotBlank @Pattern(regexp="[0-9]{11}|[0-9]{14}") String taxId,
        @NotBlank @Size(max=150) String name, @Email @Size(max=80) String email, @Valid Address address) {}
    record Prepare(@NotNull UUID draftId, @Valid Recipient recipient,
        @Pattern(regexp="[0-9]{7}|") String serviceMunicipality,
        @Pattern(regexp="[0-9]{3}|") String municipalServiceCode,
        @Pattern(regexp="[0-9]{9}|") String nbs,
        @Size(max=180000) String advancedDpsXml,
        @Size(max=10000) String federalTaxesXml,
        @Size(max=10000) String totalTaxesXml,
        @Size(max=60000) String ibsCbsXml) {
        @Override public String toString() { return "Prepare[draftId="+draftId+", fiscalData=PROTECTED]"; }
    }
    record Confirm(@NotBlank String confirmIssuerTaxId) {}
    record Cancel(@NotBlank @Pattern(regexp="1|2|9") String reasonCode,
        @NotBlank @Size(min=15,max=255) String reason, @NotBlank String confirmIssuerTaxId) {}
    record Note(UUID id, UUID issuerId, UUID draftId, String environment, String dpsId,
        String series, long dpsNumber, String status, String accessKey, String officialNumber,
        String lastCode, String lastMessage, boolean retryAllowed, OffsetDateTime createdAt,
        BigDecimal amount, LocalDate competence, String consultationUrl) {}
    record NotePage(List<Note> items,long total,int page,int size) {}
}
