package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShowDetailVO {

    private Long id;

    private String title;

    private String artist;

    private String description;

    private String city;

    private String venueName;

    private String address;

    private List<SessionVO> sessions;
}
