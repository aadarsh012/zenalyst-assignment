package com.zenalyst.housing.objection;

/** Where an objection has got to. */
public enum ObjectionStatus {

    /** Filed, awaiting adjudication. */
    OPEN,

    /**
     * Accepted. Note what this does <em>not</em> do: it changes no allotment and edits no draw. It
     * records a finding and states the remedy, and authorises a superseding draw once the
     * underlying record has been corrected.
     */
    UPHELD,

    /** Considered and refused, with a written reason. */
    REJECTED,

    /** Withdrawn by whoever filed it. */
    WITHDRAWN
}
