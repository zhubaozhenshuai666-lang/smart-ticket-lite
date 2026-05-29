package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.vo.SessionVO;
import com.zewbby.smartticket.domain.vo.ShowDetailVO;
import com.zewbby.smartticket.domain.vo.ShowListVO;
import com.zewbby.smartticket.domain.vo.TicketCategoryVO;

import java.util.List;

public interface ShowService {

    List<ShowListVO> listShows();

    ShowDetailVO getShowDetail(Long showId);

    List<SessionVO> listSessions(Long showId);

    List<TicketCategoryVO> listTicketCategories(Long sessionId);
}
