package org.arghyam.jalsoochak.scheme.statesync.model;

/**
 * A person as the upstream lists them. {@code phone} is raw upstream text and is PII: never log it
 * above DEBUG.
 *
 * @param role the upstream role slug, e.g. {@code jal-mitra}; see {@code StateVocabulary#roleOf}
 */
public record UpstreamPerson(String code, String name, String phone, String role) {

    @Override
    public String toString() {
        return "UpstreamPerson[code=" + code + ", role=" + role + "]";
    }
}
