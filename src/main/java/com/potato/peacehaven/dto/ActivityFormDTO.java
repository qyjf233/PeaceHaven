package com.potato.peacehaven.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ActivityFormDTO {
    private Long id;
    private String slug;
    private String title;
    private String summary;
    private String thumbnail;
    private String startDate;
    private String endDate;
}
