package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.vo.SessionVO;
import com.zewbby.smartticket.domain.vo.ShowDetailVO;
import com.zewbby.smartticket.domain.vo.ShowListVO;
import com.zewbby.smartticket.domain.vo.TicketCategoryVO;

import java.util.List;

public interface ShowMapper {

    List<ShowListVO> selectShowList();

    ShowDetailVO selectShowDetailById(Long showId);

    List<SessionVO> selectSessionsByShowId(Long showId);

    List<TicketCategoryVO> selectTicketCategoriesBySessionId(Long sessionId);
}
