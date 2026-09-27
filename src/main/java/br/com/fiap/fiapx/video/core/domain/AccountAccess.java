package br.com.fiap.fiapx.video.core.domain;
import java.util.UUID;
/** Response from the identity boundary; boxed fields preserve missing values. */
public record AccountAccess(UUID id, String role, Boolean active, Long credentialVersion) {}
