package com.journal.repository;

import com.journal.model.Paper;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperRepository extends MongoRepository<Paper, String> {

    /** 根据 PaperID 查询 */
    Paper findByPaperId(String paperId);

    /** 根据标题模糊搜索 */
    List<Paper> findByTitleContaining(String keyword);

    /** 根据期刊名查询 */
    List<Paper> findByJournalName(String journalName);
}
