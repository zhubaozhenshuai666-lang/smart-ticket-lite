package com.zewbby.smartticket.mapper;

import com.zewbby.smartticket.domain.entity.PerformanceSession;
import com.zewbby.smartticket.domain.entity.ShowInfo;
import com.zewbby.smartticket.domain.vo.SessionVO;
import com.zewbby.smartticket.domain.vo.ShowDetailVO;
import com.zewbby.smartticket.domain.vo.ShowListVO;
import com.zewbby.smartticket.domain.vo.TicketCategoryVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ShowMapper {

    List<ShowListVO> selectShowList();

    ShowDetailVO selectShowDetailById(Long showId);

    List<SessionVO> selectSessionsByShowId(Long showId);

    List<TicketCategoryVO> selectTicketCategoriesBySessionId(Long sessionId);

    List<ShowInfo> adminSelectShows(@Param("status") String status, @Param("limit") Integer limit);

    ShowInfo selectShowInfoById(Long id);

    int insertShow(ShowInfo showInfo);

    int updateShow(ShowInfo showInfo);

    int updateShowStatus(@Param("id") Long id, @Param("status") String status);

    List<PerformanceSession> adminSelectSessionsByShowId(Long showId);

    PerformanceSession selectSessionById(Long id);

    int insertSession(PerformanceSession session);

    int updateSession(PerformanceSession session);

    int updateSessionStatus(@Param("id") Long id, @Param("status") String status);
}
