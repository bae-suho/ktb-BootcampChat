// Next.js 서버 시작 시 실행되는 파일
export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    console.log('='.repeat(60));
    console.log('🚀 Next.js Server Started!');
    console.log('='.repeat(60));
    console.log('📍 Runtime Environment Configuration:');
    console.log('   NODE_ENV:', process.env.NODE_ENV || 'not set');
    console.log('   PORT:', process.env.PORT || 'not set');
    console.log('   NEXT_PUBLIC_API_URL:', process.env.NEXT_PUBLIC_API_URL || 'not set');
    console.log('   NEXT_PUBLIC_SOCKET_URL:', process.env.NEXT_PUBLIC_SOCKET_URL || 'not set');
    console.log('='.repeat(60));
  }
}