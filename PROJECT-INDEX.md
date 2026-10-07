# K-Flow ERP Project Index

이 문서는 K-Flow ERP의 개발 문서 위치를 안내하는 Index다.

문서의 상세 내용은 각 원본 파일을 기준으로 하며,
이 Index에서 내용을 중복 복사하지 않는다.

## Canonical Documents

### Requirements

- Current Backend Target Requirements: [REQUIREMENTS-v0.2.md](REQUIREMENTS-v0.2.md)
- Original Frontend / Mock Analysis: [REQUIREMENTS.md](REQUIREMENTS.md)

### Architecture

- Architecture Overview v0.1: [docs/architecture/architecture.md](docs/architecture/architecture.md)

### Domain Model

- ERD Overview v0.1: [docs/domain/erd-overview.md](docs/domain/erd-overview.md)

### Decisions

- ADR 0001 — Organization / Employee / Account Boundaries: [docs/adr/0001-organization-employee-account-boundaries.md](docs/adr/0001-organization-employee-account-boundaries.md)

### Detailed Design

- Organization + HR Core + IAM Detailed Design v0.1: [docs/specs/organization-hr-iam-detailed-design-v0.1.md](docs/specs/organization-hr-iam-detailed-design-v0.1.md)
- ORG-01 Company / Site Detailed Design v0.1 — O1-D01~10 ADOPTED, ORG-01 로컬 검증 완료: [docs/specs/organization-org-01-company-site-detailed-design-v0.1.md](docs/specs/organization-org-01-company-site-detailed-design-v0.1.md)

### Implementation Plan

- Implementation Plan v0.1: [docs/plans/organization-hr-iam-implementation-plan-v0.1.md](docs/plans/organization-hr-iam-implementation-plan-v0.1.md)

### ORG-01 Verification

- ORG-01 로컬 실행 근거 — 원격 CI NOT_RUN: [docs/test-evidence/organization-org-01.md](docs/test-evidence/organization-org-01.md)

## Future Documentation

다음 문서는 실제 작업이 시작될 때 추가한다.

- `docs/test-evidence/`: 실제 실행한 Test Evidence
- `PROGRESS.md`: 실제 개발 진행 기록

존재하지 않는 문서는 현재 구현된 것처럼 링크하지 않는다.

## Reading Order

프로젝트 전체를 처음 보는 경우:

1. `REQUIREMENTS-v0.2.md`
2. `docs/architecture/architecture.md`
3. `docs/domain/erd-overview.md`

특정 기능을 개발하는 경우:

1. 관련 Requirements
2. Architecture
3. ERD Overview
4. 관련 ADR / Spec
5. 실제 코드와 Test

순서로 확인한다.

## Important

- 현재 GitHub에는 개발 문서와 기존 문서용 스크린샷만 반영했다. 문서 안의 `src/` 등 소스 경로는 로컬 Frontend 분석 근거이며, 코드를 업로드하기 전에는 GitHub에서 열리지 않는다. v0.1의 로컬 분석 경로와 v0.2의 사용자 첨부 링크는 출처 기록으로 남겨 두었으며 Repository에 포함된 파일이 아니다.

- GitHub Repository의 문서를 K-Flow 개발의 기준 원본으로 사용한다.
- 아직 확정되지 않은 `[DECISION REQUIRED]`, `[OPEN]`, `[DRAFT]`는 구현 완료 또는 확정 정책으로 해석하지 않는다.
- 실제 실행하지 않은 테스트나 성능 결과를 문서에 추가하지 않는다.
