package com.infinitestack.reports.blocks.brgeo;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The 27 federative units: IBGE code, abbreviation and name. The BR Geo tables name a state in
 * different ways (the ZARC by abbreviation, the CONAB by its full name, the IBGE by the first two
 * digits of a municipality code), and a report input may come in any of them.
 */
public final class BrazilianStates {

    private BrazilianStates() {}

    public record State(String ibgeCode, String uf, String name) {}

    static final List<State> ALL = List.of(
            new State("11", "RO", "Rondônia"), new State("12", "AC", "Acre"), new State("13", "AM", "Amazonas"),
            new State("14", "RR", "Roraima"), new State("15", "PA", "Pará"), new State("16", "AP", "Amapá"),
            new State("17", "TO", "Tocantins"), new State("21", "MA", "Maranhão"), new State("22", "PI", "Piauí"),
            new State("23", "CE", "Ceará"), new State("24", "RN", "Rio Grande do Norte"), new State("25", "PB", "Paraíba"),
            new State("26", "PE", "Pernambuco"), new State("27", "AL", "Alagoas"), new State("28", "SE", "Sergipe"),
            new State("29", "BA", "Bahia"), new State("31", "MG", "Minas Gerais"), new State("32", "ES", "Espírito Santo"),
            new State("33", "RJ", "Rio de Janeiro"), new State("35", "SP", "São Paulo"), new State("41", "PR", "Paraná"),
            new State("42", "SC", "Santa Catarina"), new State("43", "RS", "Rio Grande do Sul"),
            new State("50", "MS", "Mato Grosso do Sul"), new State("51", "MT", "Mato Grosso"), new State("52", "GO", "Goiás"),
            new State("53", "DF", "Distrito Federal"));

    /** The state of a 7-digit IBGE municipality code. */
    public static Optional<State> ofMunicipality(String cdMun) {
        if (cdMun == null || cdMun.length() < 2) return Optional.empty();
        String code = cdMun.substring(0, 2);
        return ALL.stream().filter(s -> s.ibgeCode().equals(code)).findFirst();
    }

    /** A state by abbreviation, name (accents and case ignored) or IBGE code. */
    public static Optional<State> find(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String folded = fold(text);
        return ALL.stream()
                .filter(s -> fold(s.uf()).equals(folded) || fold(s.name()).equals(folded) || s.ibgeCode().equals(text.trim()))
                .findFirst();
    }

    /** Lower case, without accents nor anything but letters and digits: "São Paulo" and "sao paulo", "2ª" and "2a" fold alike. */
    public static String fold(String text) {
        if (text == null) return "";
        String plain = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
