package com.zenalyst.housing.platform.security;

/**
 * Who may do what.
 *
 * <p>The division follows one principle: everything that lets somebody <em>influence</em> the
 * outcome is restricted, and everything that lets somebody <em>check</em> it is open. A
 * verification endpoint only the authority can run proves nothing, so the endpoints a journalist or
 * a court would use need no credentials at all.
 */
public enum Role {

    /**
     * An applicant, authenticated as one specific application.
     *
     * <p>The token's subject is the application number, and it grants access to that application
     * and no other. This is the JWT principal doing real work rather than merely proving somebody
     * logged in.
     */
    APPLICANT,

    /**
     * Counter and back-office staff: typing up paper forms, verifying certificates, judging fuzzy
     * duplicate matches.
     */
    OPERATOR,

    /** Read access to any applicant's file, for investigating a complaint. Changes nothing. */
    AUDITOR,

    /**
     * The registrar: publishing quota matrices, freezing the register, running the draw,
     * adjudicating objections. Everything that decides an outcome rather than recording a fact.
     */
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
