// Compat: a implementacao vive em hooks/useSuperAdminGuard.
// As paginas de /plataforma importam daqui; o hook agora tambem
// retorna { loading } (usado pelo layout da plataforma).
export { useSuperAdminGuard } from '@/hooks/useSuperAdminGuard'
