package com.potato.peacehaven.dto;

import com.potato.peacehaven.enums.ActivityStatus;
import com.potato.peacehaven.enums.TemplateType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ActivityFormDTO {
    private Long id;
    private String title;
    private String summary;
    private String content;
    private String thumbnail;
    private TemplateType templateType;
    private ActivityStatus status;
    private String startDate;
    private String endDate;
    private String configJson;
}
