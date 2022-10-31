package com.wafflestudio.seminar.core.user.service

import com.querydsl.jpa.impl.JPAQueryFactory
import com.wafflestudio.seminar.common.*
import com.wafflestudio.seminar.core.user.api.request.SeminarRequest
import com.wafflestudio.seminar.core.user.api.request.EditProfileRequest
import com.wafflestudio.seminar.core.user.api.request.ParticipantRequest
import com.wafflestudio.seminar.core.user.api.request.SignUpRequest
import com.wafflestudio.seminar.core.user.api.response.SeminarResponse
import com.wafflestudio.seminar.core.user.database.ParticipantProfileEntity
import com.wafflestudio.seminar.core.user.database.SeminarEntity
import com.wafflestudio.seminar.core.user.database.UserEntity
import com.wafflestudio.seminar.core.user.database.UserSeminarEntity
import com.wafflestudio.seminar.core.user.domain.*
import com.wafflestudio.seminar.core.user.repository.*
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime


@Service
@Transactional
class UserServiceImpl(
    private val userRepository: UserRepository,
    private val participantProfileRepository: ParticipantProfileRepository,
    private val seminarRepository: SeminarRepository,
    private val userSeminarRepository: UserSeminarRepository,
    private val customSeminarRepository: CustomSeminarRepository,
    private val customUserSeminarRepository: CustomUserSeminarRepository,
    private val passwordEncoder: PasswordEncoder
) : UserService {

    override fun signUp(signUpRequest: SignUpRequest): Long {
        if (userRepository.findByEmail(signUpRequest.email!!) != null) {
            throw Seminar409("Email is already in use")
        }
        val userEntity = signUpRequest.toUserEntity()
        userEntity.password = passwordEncoder.encode(userEntity.password)
        userEntity.lastLogin = LocalDateTime.now()
        userRepository.save(userEntity)
        return userEntity.id
    }

    override fun login(email: String, password: String) {
        val userEntity = userRepository.findByEmail(email) ?: throw Seminar404("No existing user with email: ${email}")
        if (!passwordEncoder.matches(password, userEntity.password)) {
            throw Seminar401("Incorrect password")
        }
        userEntity.lastLogin = LocalDateTime.now()
    }

    @Transactional(readOnly = true)
    override fun getProfile(userId: Long): User {
        val userEntity = userRepository.findById(userId).get()
        return userEntity.toDTO()
    }

    override fun editProfile(userId: Long, editProfileRequest: EditProfileRequest) {
        val userEntity = userRepository.findById(userId).get()
        if (editProfileRequest.username != null) {
            userEntity.username = editProfileRequest.username
        }
        if (editProfileRequest.password != null) {
            userEntity.password = editProfileRequest.password
        }
        if (userEntity.participantProfile != null) {
            userEntity.participantProfile!!.university = editProfileRequest.university
        }
        if (userEntity.instructorProfile != null) {
            userEntity.instructorProfile!!.company = editProfileRequest.company
            userEntity.instructorProfile!!.year = editProfileRequest.year
        }
    }

    override fun registerParticipantProfile(userId: Long, participantRequest: ParticipantRequest) {
        val userEntity = userRepository.findById(userId).get()
        if (userEntity.participantProfile != null) {
            throw Seminar409("You have participant profile already")
        }
        val participantProfileEntity =
            ParticipantProfileEntity(participantRequest.university, participantRequest.isRegisterd)
        participantProfileEntity.addUser(userEntity)
        participantProfileRepository.save(participantProfileEntity)
    }

    override fun createSeminar(userId: Long, seminarRequest: SeminarRequest): Seminar {
        val userEntity = userRepository.findById(userId).get()
        val seminarEntity = SeminarEntity(
            seminarRequest.name,
            seminarRequest.capacity,
            seminarRequest.count,
            seminarRequest.time,
            seminarRequest.online
        )
        val userSeminarEntity = UserSeminarEntity(userEntity, seminarEntity, Role.INSTRUCTOR)
        seminarEntity.addUserSeminar(userSeminarEntity)
        seminarRepository.save(seminarEntity)
        userSeminarRepository.save(userSeminarEntity)
        return seminarEntity.toDTO()
    }

    override fun editSeminar(seminarRequest: SeminarRequest): Seminar {
        val seminarEntity = seminarRepository.findById(seminarRequest.id!!).get()
        seminarEntity.name = seminarRequest.name
        seminarEntity.capacity = seminarRequest.capacity
        seminarEntity.count = seminarRequest.count
        seminarEntity.time = seminarRequest.time
        seminarEntity.online = seminarRequest.online
        return seminarEntity.toDTO()
    }

    @Transactional(readOnly = true)
    override fun getSeminar(seminarId: Long): SeminarResponse {
        val seminarEntity = seminarRepository.findById(seminarId).get()
        return seminarEntity.toSeminarResponse()
    }

    @Transactional(readOnly = true)
    override fun getSeminars(name: String, order: String): List<SeminarResponse> {
        val seminarEntities = customSeminarRepository.findByNameWithOrder("pr", "earliest")
        val seminars = ArrayList<SeminarResponse>()
        for (seminarEntity in seminarEntities) {
            seminars.add(seminarEntity.toSeminarResponse())
        }
        return seminars
    }

    override fun joinSeminar(userId: Long, seminarId: Long, role: Role): Seminar {
        val seminarEntity = seminarRepository.findByIdOrNull(seminarId) ?: throw Seminar404("Seminar Not Found")
        val userEntity = userRepository.findById(userId).get()
        when (role) {
            Role.PARTICIPANT -> {
                if (userEntity.participantProfile == null) {
                    throw Seminar403("You don't have participant profile")
                }
                if (!userEntity.participantProfile!!.isRegistered) {
                    throw Seminar403("You are not registered")
                }
                if (seminarEntity.capacity == seminarEntity.userSeminars.size) {
                    throw Seminar400("Sorry, this seminar is full")
                }
            }
            Role.INSTRUCTOR -> {
                if (userEntity.instructorProfile == null) {
                    throw Seminar403("You don't have instructor profile")
                }
                for (userSeminar in userEntity.userSeminars) {
                    if (userSeminar.role == Role.INSTRUCTOR) {
                        throw Seminar400("You are already instructing other seminar")
                    }
                }
            }
            else -> throw Seminar400("No Such Role")
        }
        for (userSeminar in userEntity.userSeminars) {
            if (userSeminar.seminar == seminarEntity && userSeminar.isActive) {
                throw Seminar400("You already joined this seminar")
            } else if (userSeminar.seminar == seminarEntity && !userSeminar.isActive) {
                throw Seminar400("You cannot join again after dropping seminar")
            }
        }
        val userSeminar = UserSeminarEntity(userEntity, seminarEntity, role)
        seminarEntity.addUserSeminar(userSeminar)
        userEntity.addUserSeminar(userSeminar)
        userSeminarRepository.save(userSeminar)
        return seminarEntity.toDTO()
    }

    override fun dropSeminar(userId: Long, seminarId: Long): Seminar {
        val seminarEntity = seminarRepository.findByIdOrNull(seminarId) ?: throw Seminar404("Seminar Not Found")
        val userSeminarEntity =
            customUserSeminarRepository.findByUserIdAndSeminarId(userId, seminarId) ?: throw Seminar200("")
        userSeminarEntity.isActive = false
        userSeminarEntity.droppedAt = LocalDateTime.now()
        return seminarEntity.toDTO()
    }
}