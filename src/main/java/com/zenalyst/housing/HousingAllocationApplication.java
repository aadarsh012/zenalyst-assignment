package com.zenalyst.housing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Backend for a public housing scheme allocation: ~4,000 applications for 600 flats,
 * allocated by published rules and required to withstand challenge by an applicant,
 * a newspaper, or a court.
 *
 * <p>The organising constraint of this codebase is that every published output must be
 * independently re-derivable from published inputs. See {@code README.md} and {@code adr/}.
 */
@SpringBootApplication
public class HousingAllocationApplication {

    public static void main(String[] args) {
        SpringApplication.run(HousingAllocationApplication.class, args);
    }
}
