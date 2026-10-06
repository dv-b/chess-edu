package com.chessedu.platform.identity.repo;

import com.chessedu.platform.identity.model.ParentLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ParentLinkRepository extends JpaRepository<ParentLink, ParentLink.ParentLinkId> {

    List<ParentLink> findByIdParentUserId(UUID parentUserId);

    List<ParentLink> findByIdStudentUserId(UUID studentUserId);
}
