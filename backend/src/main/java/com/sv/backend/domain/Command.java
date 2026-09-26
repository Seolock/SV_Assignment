package com.sv.backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class Command extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Device device;

    @Column(unique = true, nullable = false)
    private String commandId;

    private String type;    // SET_LED
    private String value;   // on / off
    private String status;  // PENDING / ACKED / FAILED
    private String source;  // cli / rule
    private String reason;  // FAILED 사유

}
