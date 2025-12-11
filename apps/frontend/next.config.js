/** @type {import('next').NextConfig} */

// 서버 시작 시 환경변수 로그 출력
console.log('='.repeat(60));
console.log(`🚀 Next.js Server Starting... NEXT_PUBLIC_API_URL:\', process.env.NEXT_PUBLIC_API_URL || \'not set`);
console.log('='.repeat(60));
console.log('📍 Environment Configuration:');
console.log('   NODE_ENV:', process.env.NODE_ENV || 'not set');
console.log('   NEXT_PUBLIC_API_URL:', process.env.NEXT_PUBLIC_API_URL || 'not set');
console.log('   NEXT_PUBLIC_SOCKET_URL:', process.env.NEXT_PUBLIC_SOCKET_URL || 'not set');
console.log('='.repeat(60));

const nextConfig = {
    reactStrictMode: false, // 에러 처리 문제 해결을 위해 일시적으로 비활성화
    transpilePackages: ['@vapor-ui/core', '@vapor-ui/icons'],
    // Docker 빌드를 위한 standalone 출력 모드 (개발 환경에는 영향 없음)
    output: 'standalone',
    // monorepo에서 standalone 빌드 시 중첩 경로 방지
    outputFileTracingRoot: __dirname,
    // 개발 환경에서의 에러 오버레이 설정
    devIndicators: {
        buildActivity: true,
        buildActivityPosition: 'bottom-right'
    },
    // 개발 환경에서만 더 자세한 에러 로깅
    ...(process.env.NODE_ENV === 'development' && {
        experimental: {
            forceSwcTransforms: true
        }
    })
};

module.exports = nextConfig;
