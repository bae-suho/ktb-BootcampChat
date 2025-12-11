# CI/CD 설정 가이드

## 개요

GitHub Actions를 통한 AWS EC2 자동 배포

**트리거**: `deploy` 브랜치 푸시
**레지스트리**: Docker Hub (wafriend1031)
**배포 방식**: SSH + AWS Target Groups

---

## 필수 GitHub Secrets

### 기본 Secrets (필수)

| Secret 이름 | 설명 | 값 |
|-------------|------|-----|
| `DOCKER_USERNAME` | Docker Hub 사용자명 | `wafriend1031` |
| `DOCKER_PASSWORD` | Docker Hub 토큰 | [Docker Hub](https://hub.docker.com/)에서 생성 |
| `EC2_SSH_PRIVATE_KEY` | SSH 개인키 | `cat ~/.ssh/your-key.pem` |
| `EC2_SSH_USER` | SSH 사용자명 | `ubuntu` 또는 `ec2-user` |
| `BACKEND_ENV_FILE` | Base64 인코딩된 .env | `cd apps/backend && cat .env \| base64 \| tr -d '\n'` |
| `NEXT_PUBLIC_API_URL` | 프론트엔드 API URL | 팀 도메인 사용 |
| `NEXT_PUBLIC_SOCKET_URL` | 프론트엔드 Socket URL | 팀 도메인 사용 |

### AWS Target Group Secrets (다중 인스턴스용)

| Secret 이름 | 설명 | 값 |
|-------------|------|-----|
| `AWS_ACCESS_KEY_ID` | AWS Access Key | IAM에서 생성한 Access Key |
| `AWS_SECRET_ACCESS_KEY` | AWS Secret Key | IAM에서 생성한 Secret Key |
| `BACKEND_TARGET_GROUP_NAME` | Backend TG 이름 | `be-api-tg` |
| `FRONTEND_TARGET_GROUP_NAME` | Frontend TG 이름 | `fe-tg` |
| `AWS_REGION` | AWS 리전 (선택) | `ap-northeast-2` |

---

## 빠른 설정

### 1. GitHub Secrets 추가

```bash
# 이동: Repository → Settings → Secrets and variables → Actions
# 클릭: New repository secret
# 위 표의 각 Secret 추가
```

### 2. Backend .env 준비

```bash
cd apps/backend

# .env를 Base64로 인코딩
cat .env | base64 | tr -d '\n'

# 출력값을 BACKEND_ENV_FILE Secret에 복사
```

### 3. 배포

```bash
git checkout deploy
git merge main
git push origin deploy

# 배포 확인: GitHub → Actions 탭
```

---

## 워크플로우

### Backend 배포 (`deploy-be.yml`)

**트리거**:
- `deploy` 브랜치 푸시
- `apps/backend/**` 경로 변경

**단계**:
1. 멀티플랫폼 Docker 이미지 빌드 (amd64, arm64)
2. Docker Hub에 푸시 (태그: `latest`, `<git-sha>`)
3. AWS Target Group에서 healthy 인스턴스 조회
4. SSH로 각 인스턴스에 순차 배포 (롤링 배포)
5. 헬스체크 `http://localhost:5001/api/health`
6. 실패 시 자동 롤백

### Frontend 배포 (`deploy-fe.yml`)

**트리거**:
- `deploy` 브랜치 푸시
- `apps/frontend/**` 경로 변경

**단계**:
1. 멀티플랫폼 Docker 이미지 빌드 (amd64, arm64)
2. Docker Hub에 푸시 (태그: `latest`, `<git-sha>`)
3. AWS Target Group에서 healthy 인스턴스 조회
4. SSH로 각 인스턴스에 순차 배포 (롤링 배포)
5. 헬스체크 `http://localhost:3000`
6. 실패 시 자동 롤백

---

## AWS IAM 설정 (Target Group 사용 시)

### IAM 사용자 생성

```bash
aws iam create-user --user-name github-actions-deploy
```

### 정책 연결

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "elasticloadbalancing:DescribeTargetGroups",
      "elasticloadbalancing:DescribeTargetHealth",
      "ec2:DescribeInstances"
    ],
    "Resource": "*"
  }]
}
```

```bash
# 위 내용을 deploy-policy.json으로 저장
aws iam create-policy --policy-name GitHubActionsDeployPolicy --policy-document file://deploy-policy.json
aws iam attach-user-policy --user-name github-actions-deploy --policy-arn <policy-arn>
```

### Access Key 생성

```bash
aws iam create-access-key --user-name github-actions-deploy

# AccessKeyId와 SecretAccessKey를 GitHub Secrets에 추가
```

---

## 배포 플로우

```
개발자 푸시
      ↓
  deploy 브랜치
      ↓
GitHub Actions
      ├── Docker 이미지 빌드
      ├── Docker Hub 푸시
      ├── AWS Target Group 조회
      └── 롤링 배포
          ├── Instance 1 → 배포 → 헬스체크 ✅
          ├── (10초 대기)
          ├── Instance 2 → 배포 → 헬스체크 ✅
          └── Instance 3 → 배포 → 헬스체크 ✅
```

---

## 트러블슈팅

### SSH 연결 실패

```bash
# SSH 키 형식 확인 (BEGIN/END 라인 포함)
cat ~/.ssh/your-key.pem

# 연결 테스트
ssh -i ~/.ssh/your-key.pem ubuntu@<ec2-ip>
```

### 헬스체크 실패

```bash
# EC2에 SSH 접속
ssh ubuntu@<ec2-ip>

# 로그 확인
docker logs ktb-backend --tail 100  # Backend
docker logs ktb-frontend --tail 100  # Frontend

# 헬스 엔드포인트 테스트
curl http://localhost:5001/api/health  # Backend
curl http://localhost:3000              # Frontend
```

### Target Group 문제

```bash
# TG 이름 확인
aws elbv2 describe-target-groups

# Healthy 인스턴스 확인
aws elbv2 describe-target-health --target-group-arn <arn>
```

### 롤백

```bash
# GitHub Actions 히스토리에서 이전 SHA 확인
PREV_SHA=abc1234

# 각 인스턴스에 SSH 접속
ssh ubuntu@<ec2-ip>
docker pull wafriend1031/ktb-bootcamp-chat-backend:$PREV_SHA
docker stop ktb-backend && docker rm ktb-backend
docker run -d --name ktb-backend --restart unless-stopped \
  -p 5001:5001 -p 5002:5002 --env-file ~/ktb-backend/.env \
  wafriend1031/ktb-bootcamp-chat-backend:$PREV_SHA
```

---

## 모니터링

### GitHub Actions

```
Repository → Actions → 워크플로우 선택 → 로그 확인
```

### 배포 로그

확인 사항:
- ✅ Healthy instances found
- 📦 Deploying to instance X/Y
- ✅ Health check passed
- 🎉 All deployed successfully

---

## 체크리스트

배포 전 확인:

- [ ] GitHub Secrets 모두 설정 완료
- [ ] Backend .env 인코딩 및 추가 완료
- [ ] SSH 키를 모든 EC2 인스턴스에 추가
- [ ] 모든 EC2에 Docker 설치 완료
- [ ] AWS IAM 사용자 생성 (TG 사용 시)
- [ ] Target Groups 설정 완료 (TG 사용 시)
- [ ] 헬스 엔드포인트 동작 확인

---

**마지막 업데이트**: 2025-12-11
