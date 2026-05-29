package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShowListVO {

    private Long id;

    private String title;

    private String artist;

    private String city;

    private String venueName;

    private LocalDateTime nearestStartTime;
}
