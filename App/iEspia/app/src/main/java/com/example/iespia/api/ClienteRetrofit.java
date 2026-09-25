package com.example.iespia.api;

import android.content.Context;
import android.content.SharedPreferences;
import okhttp3.Authenticator;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.io.IOException;

// Clase Singleton encargada de configurar y proveer la instancia de Retrofit (cliente HTTP).
public class ClienteRetrofit {

    // URL base de la API del servidor.
    private static final String URL_BASE = "http://10.0.2.2:6010/"; // emulador Android (10.0.2.2 = host machine)

    // Instancia única de Retrofit.
    private static Retrofit retrofit = null;

    // Instancia de la interfaz de servicios API.
    private static ServicioApi servicioApi = null;

    /**
     * Obtiene la instancia de ServicioApi configurada.
     * Si no existe, la crea con interceptores de log y autenticación.
     */
    public static ServicioApi obtenerServicioApi(Context contexto) {
        if (servicioApi == null) {
            // Configura el interceptor para ver los logs de las peticiones HTTP en consola
            // (útil para depurar).
            HttpLoggingInterceptor registroHttp = new HttpLoggingInterceptor();
            registroHttp.setLevel(HttpLoggingInterceptor.Level.BODY); // Muestra cuerpo, cabeceras, etc.

            // Construye el cliente OkHttp con los interceptores.
            OkHttpClient cliente = new OkHttpClient.Builder()
                    .addInterceptor(registroHttp)
                    // Añade nuestro autenticador personalizado para manejar errores 401 y renovar
                    // tokens.
                    .authenticator(new AutenticadorToken(contexto))
                    .build();

            // Construye la instancia de Retrofit.
            retrofit = new Retrofit.Builder()
                    .baseUrl(URL_BASE) // Define la URL base.
                    .addConverterFactory(GsonConverterFactory.create()) // Usa Gson para convertir JSON a objetos Java
                                                                        // automáticamente.
                    .client(cliente) // Asigna el cliente OkHttp configurado.
                    .build();

            // Crea la implementación de la interfaz ServicioApi.
            servicioApi = retrofit.create(ServicioApi.class);
        }
        return servicioApi;
    }

    // Sobrecarga por compatibilidad (si se llamara sin contexto, lanzaría error).
    public static ServicioApi obtenerServicioApi() {
        if (servicioApi == null) {
            throw new IllegalStateException("ClienteRetrofit debe ser inicializado con Contexto primero!");
        }
        return servicioApi;
    }

    /**
     * Clase interna que implementa Authenticator de OkHttp.
     * Su función es interceptar respuestas 401 (No Autorizado) y tratar de renovar
     * el token automáticamente.
     */
    private static class AutenticadorToken implements Authenticator {
        private final Context contexto;

        // Constructor que recibe el contexto (para acceder a SharedPreferences).
        public AutenticadorToken(Context contexto) {
            this.contexto = contexto.getApplicationContext();
        }

        @Override
        public Request authenticate(Route ruta, Response respuesta) throws IOException {
            // Evita bucles infinitos: Si ya hemos fallado 3 veces consecutivas, nos
            // rendimos.
            if (contarRespuestas(respuesta) >= 3) {
                return null;
            }

            // Aquí debería haber lógica para comprobar si la petición ya tiene el token
            // nuevo,
            // pero para simplificar, intentamos renovar directamente.

            // Obtenemos el token antiguo guardado en preferencias.
            SharedPreferences preferencias = contexto.getSharedPreferences("iEspiaPrefs", Context.MODE_PRIVATE);
            String tokenAntiguo = preferencias.getString("authToken", null);

            // Si no hay token guardado, no podemos renovar nada.
            if (tokenAntiguo == null) {
                return null;
            }

            // Para renovar el token, necesitamos hacer una llamada síncrona a la API.
            // Creamos una instancia separada de Retrofit/ServicioApi para esta llamada
            // específica
            // para evitar problemas de recursividad o interceptores circulares.
            ServicioApi servicioAuth = new Retrofit.Builder()
                    .baseUrl(URL_BASE)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(ServicioApi.class);

            try {
                // Llamada síncrona (.execute()) al endpoint de renovación.
                retrofit2.Response<ServicioApi.RespuestaLogin> respuestaRenovacion = servicioAuth
                        .renovarToken(tokenAntiguo)
                        .execute();

                // Si la renovación fue exitosa (200 OK) y recibimos cuerpo.
                if (respuestaRenovacion.isSuccessful() && respuestaRenovacion.body() != null) {
                    // Preparamos el nuevo token con el prefijo "Bearer ".
                    String nuevoToken = "Bearer " + respuestaRenovacion.body().token;

                    // Guardamos el nuevo token en SharedPreferences para futuras peticiones.
                    preferencias.edit().putString("authToken", nuevoToken).apply();

                    // Retornamos una NUEVA petición (Request) basada en la original fallida,
                    // pero con la cabecera 'Authorization' actualizada con el nuevo token.
                    // Esto hace que OkHttp reintente automáticamente la llamada original.
                    return respuesta.request().newBuilder()
                            .header("Authorization", nuevoToken)
                            .build();
                }
            } catch (Exception e) {
                // Si falla la renovación , no hacemos nada.
                // El método retornará null finalmente.
            }

            return null; // Si llegamos aquí, nos rendimos. El error 401 se propagará a la app.
        }

        // Método auxiliar para contar cuántas veces se ha reintentado esta respuesta.
        private int contarRespuestas(Response respuesta) {
            int resultado = 1;
            while ((respuesta = respuesta.priorResponse()) != null) {
                resultado++;
            }
            return resultado;
        }
    }
}
