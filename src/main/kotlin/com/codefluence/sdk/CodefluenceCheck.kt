package com.codefluence.sdk

/** 한 가지 설치 점검 결과. [Codefluence.validate]가 돌려줍니다. 통과하면 확인한 내용, 실패하면 고칠 방법이 [hint]에 들어 있습니다. */
data class CodefluenceCheck(val name: String, val passed: Boolean, val hint: String)
