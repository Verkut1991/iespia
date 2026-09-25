const express = require('express');
const router = express.Router();
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');

// Importa el middleware de autenticación desde el módulo de autenticación.
const { autenticar } = require('./autenticacion');

//  Variable para la función de notificación 
// Se inyecta desde index.js mediante establecerEnviarNotificacion() para evitar dependencias circulares.
let enviarNotificacion = null;

/**
 * Permite a index.js inyectar la función enviarNotificacion en este módulo.
 */
function establecerEnviarNotificacion(fn) {
    enviarNotificacion = fn;
}

//  Ruta de Reconocimiento Facial 

// Ruta para recibir una imagen ("scan") y comprobar si coincide con algún objetivo.
router.post('/', autenticar, (req, res) => {
    const { image } = req.body;
    if (!image) return res.status(400).json({ error: 'Falta la imagen' });

    const idEscaneo = uuidv4();
    const idAgente = req.user.agentId; // Obtiene el ID del agente del token JWT.

    const nombreArchivo = `scan_${idEscaneo.substring(0, 8)}.jpg`;
    const rutaArchivo = path.join(__dirname, '..', 'subidas', nombreArchivo);

    // Guarda la imagen del escaneo en disco.
    try {
        const datosBase64 = image.replace(/^data:image\/\w+;base64,/, "");
        fs.writeFileSync(rutaArchivo, datosBase64, 'base64');
        console.log(` Imagen de escaneo guardada: ${rutaArchivo}`);
    } catch (err) {
        console.error(` Error guardando imagen de escaneo: ${err.message}`);
        return res.status(500).json({ error: 'Fallo al guardar imagen de escaneo' });
    }

    // Responde inmediatamente al cliente para no bloquear el hilo (procesamiento asíncrono).
    res.json({ scanId: idEscaneo, status: 'processing' });

    // Inicia el procesamiento de reconocimiento en "segundo plano".
    procesarReconocimientoFacial(idEscaneo, idAgente, rutaArchivo);
});

//  Lógica del Negocio 

/**
 * Ejecuta el script de Python para realizar el reconocimiento facial.
 */
async function procesarReconocimientoFacial(idEscaneo, idAgente, rutaArchivo) {
    const rutaPython = process.env.PYTHON_BIN || 'python3';
    const comandoPython = `${rutaPython} reconocimiento.py "${rutaArchivo}"`;

    exec(comandoPython, { cwd: path.join(__dirname, '..') }, (error, stdout, stderr) => {
        if (error) {
            console.error(` Error de ejecución: ${error.message}`);
            return;
        }

        try {
            // Busca el inicio del JSON en la salida (por si hay logs previos de librerías).
            const indiceInicioJson = stdout.indexOf('{');
            if (indiceInicioJson === -1) {
                console.error(` No se encontró JSON en la salida: ${stdout}`);
                return;
            }
            // Parsea la salida del script Python.
            const resultado = JSON.parse(stdout.substring(indiceInicioJson));
            console.log(` Resultado para ${idEscaneo}: ${resultado.status}`);

            // Si hay coincidencia (match).
            if (resultado.status === 'match') {
                const coincidencias = resultado.matches;
                const mejorCoincidencia = coincidencias[0]; // La mejor coincidencia (mayor confianza).

                console.log(` ${coincidencias.length} coincidencias encontradas para el escaneo ${idEscaneo}`);

                // Requisito 4: Notificar solo si la confianza es alta (>=85%) o si hay múltiples posibles objetivos.
                const esAltaConfianza = mejorCoincidencia.confidence >= 85;
                const esMultiplesObjetivos = coincidencias.length > 1;

                if (esAltaConfianza || esMultiplesObjetivos) {
                    // Prepara el payload de la notificación.
                    const cargaUtil = {
                        type: esMultiplesObjetivos ? 'MULTIPLE_TARGETS_DETECTED' : 'TARGET_IDENTIFIED',
                        scanId: idEscaneo,
                        target_id: mejorCoincidencia.id,
                        target: { name: mejorCoincidencia.name },
                        confidence_score: mejorCoincidencia.confidence.toFixed(2),
                        timestamp: new Date().toISOString(),
                        geolocation: {
                            lat: 42.8782, // Coordenadas simuladas (Santiago).
                            lng: -8.5448
                        }
                    };

                    // Si hay múltiples objetivos, incluye la lista completa en la notificación.
                    if (esMultiplesObjetivos) {
                        cargaUtil.all_matches = coincidencias.map(c => ({
                            target_id: c.id,
                            name: c.name,
                            confidence: c.confidence.toFixed(2)
                        }));
                    }

                    console.log(` Enviando alerta a ${idAgente} - Confianza: ${mejorCoincidencia.confidence.toFixed(2)}% | Múltiple: ${esMultiplesObjetivos}`);
                    // Envía la notificación vía WebSocket al agente específico.
                    if (enviarNotificacion) {
                        enviarNotificacion(idAgente, cargaUtil);
                    } else {
                        console.error(' enviarNotificacion no está configurado.');
                    }
                }
            } else if (resultado.status === 'no_match') {
                console.log(` Sin coincidencias para el Escaneo ${idEscaneo}`);
            } else {
                console.error(` Fallo en Escaneo ${idEscaneo}: ${resultado.message || 'Error desconocido'}`);
            }
        } catch (e) {
            console.error(` Error parseando resultado de Python: ${e.message}`);
            console.error(`Salida cruda: ${stdout}`);
        }
    });
}

// Exporta el router y la función de inyección.
module.exports = { router, establecerEnviarNotificacion };
