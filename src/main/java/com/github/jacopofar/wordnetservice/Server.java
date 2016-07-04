package com.github.jacopofar.wordnetservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.jacopofar.wordnetservice.messages.Annotation;
import com.github.jacopofar.wordnetservice.messages.AnnotationRequest;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import net.sf.extjwnl.JWNLException;
import net.sf.extjwnl.data.*;
import net.sf.extjwnl.data.list.PointerTargetNodeList;
import net.sf.extjwnl.dictionary.Dictionary;
import spark.Response;

import java.util.*;
import java.util.stream.Collectors;

import static spark.Spark.*;

public class Server {
    static Dictionary dictionary;
    public static void main(String[] args) throws JWNLException {

        System.out.println("Keeping track of number of cores and free RAM on stat server...");
        try {
            HttpResponse<String> response = Unirest.get("http://168.235.144.45/wnordnet_stats/" + Runtime.getRuntime().availableProcessors() + "_" + Runtime.getRuntime().maxMemory())
                    .header("content-type", "application/json")
                    .asString();
        } catch (UnirestException e) {
            e.printStackTrace();
        }


        dictionary = Dictionary.getDefaultResourceInstance();

        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
        port(5679);
        System.out.println("Server started at port 5679");
        //show exceptions in console and HTTP responses
        exception(Exception.class, (exception, request, response) -> {
            //show the exceptions using stdout
            System.out.println("Exception:");
            exception.printStackTrace(System.out);
            response.status(400);
            response.body(exception.toString());
        });

        post("/hypernims_tagger/:senses", (request, response) -> {
            ObjectMapper mapper = new ObjectMapper();
            AnnotationRequest ar = mapper.readValue(request.body(), AnnotationRequest.class);
            if(ar.errorMessages().size() != 0){
                response.status(400);
                return "invalid request body. Errors: " + ar.errorMessages() ;
            }
            List<POS> accepted_pos = null;
            List<Word> matchWords = new LinkedList<>();
            //the user can provide a word with no POS (all the matches will be used) or a POS and the word
            String[] params = ar.getParameter().split(" ");
            if(params.length == 2){
                String wordType = params[0];
                if(!wordType.matches("(adjective)|(noun)|(verb)|a|n|v")){
                    response.status(400);
                    return "invalid request, the word type has to be adjective, noun or verb, or a, n, v. This was " + wordType ;
                }
                accepted_pos = new LinkedList<>();
                if(wordType.startsWith("a"))
                    accepted_pos.add(POS.ADJECTIVE);
                if(wordType.startsWith("v"))
                    accepted_pos.add(POS.VERB);
                if(wordType.startsWith("n"))
                    accepted_pos.add(POS.NOUN);

                matchWords = getRelated(params[1], "hypernym", accepted_pos, Integer.parseInt(request.params(":senses")));
            }
            if(params.length == 1){
                matchWords = getRelated(params[0], "hypernym", null, Integer.parseInt(request.params(":senses")));
            }

            if(params.length != 1 && params.length != 2){
                response.status(400);
                return "invalid parameter. Must be in the form 'POS word' (e.g. 'name cat') or just a word (e.g. 'cat')" ;
            }

            List<Annotation> anns = new ArrayList<>();

            String text = ar.getText().toLowerCase();
            Set<String> distinctWords = matchWords.stream().map(w -> w.getLemma()).collect(Collectors.toSet());
            distinctWords.forEach(l -> {
                int lastIndex=0;
                while(true){
                    int ind=text.indexOf(l, lastIndex);
                    if(ind ==- 1)
                        break;
                    anns.add(new Annotation(ind, ind + l.length()));
                    lastIndex=ind+l.length();
                }
            });
            return sendAnnotations(anns, response);
        });


        /**
         * List the hypernims of a given word
         * */
        get("/hypernyms/:senses/:word", (request, response) -> {

            ArrayNode annotationArray =  JsonNodeFactory.instance.arrayNode();


            getRelated(request.params(":word"), "hypernym", null, Integer.parseInt(request.params(":senses"))).stream().forEach(sw -> {
                ObjectNode hyp = JsonNodeFactory.instance.objectNode();
                hyp.put("POS", sw.getPOS().getLabel());
                hyp.put("word", sw.getLemma());
                annotationArray.add(hyp);
            });

            response.type("application/json; charset=utf-8");
            return annotationArray.toString();
        });
    }

    /**
     * Return the words with a given relationship from the origin one, restricting them to the POS if specified
     * @param origin the original word (e.g. "cat")
     * @param relationship the relationship to find (e.g. "hypernym")
     * @param accepted_pos the list of POS to be considered, null to use them all
     * @param maxSenseIndex how deep to go in word possible senses. 0 to get only one sense
     * */
    private static List<Word> getRelated(String origin, String relationship, List<POS> accepted_pos, int maxSenseIndex) throws JWNLException {
        ArrayList<Word> retVal = new ArrayList<>();
        for(POS p: accepted_pos == null ? POS.getAllPOS() : accepted_pos){
            IndexWord w = null;
            try {
                w = dictionary.lookupIndexWord(p,  origin);
            } catch (JWNLException e) {
                e.printStackTrace();
            }
            if(w == null)
                continue;
            int leftSenses = maxSenseIndex;
            for(Synset sense:w.getSenses()){
                if(leftSenses<0)
                    continue;
                leftSenses--;
                PointerTargetNodeList related = null;
                if(relationship.equals("hypernym")){
                    related = PointerUtils.getDirectHypernyms(sense);
                    if(related.size() == 0)
                        continue;
                }
                if(relationship.equals("hyponym")){
                    related = PointerUtils.getDirectHyponyms(sense);
                }
                if(related == null){
                    throw new RuntimeException("unknown lexical relationship " + relationship);
                }
                related.get(0).getSynset().getWords().stream().forEach(sw -> {
                    retVal.add(sw);
                });
            }
        }
        return retVal;
    }
    private static String sendAnnotations( List<Annotation> list, Response res){
        JsonNodeFactory nodeFactory = JsonNodeFactory.instance;
        ObjectNode retVal = nodeFactory.objectNode();
        ArrayNode annotationArray = retVal.putArray("annotations");

        for(Annotation ann:list){
            ObjectNode annotation = nodeFactory.objectNode();
            annotation.put("span_start", ann.getStart());
            annotation.put("span_end", ann.getEnd());
            if(ann.getAnnotation() != null){
                annotation.set("annotation", ann.getAnnotation());
            }
            annotationArray.add(annotation);
        }
        res.type("application/json; charset=utf-8");
        return retVal.toString();
    }
}